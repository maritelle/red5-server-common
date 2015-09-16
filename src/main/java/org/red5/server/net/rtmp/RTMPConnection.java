/*
 * RED5 Open Source Flash Server - https://github.com/Red5/
 * 
 * Copyright 2006-2015 by respective authors (see below). All rights reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.red5.server.net.rtmp;

import java.beans.ConstructorProperties;
import java.util.BitSet;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.mina.core.buffer.IoBuffer;
import org.red5.server.BaseConnection;
import org.red5.server.api.Red5;
import org.red5.server.api.event.IEvent;
import org.red5.server.api.scope.IScope;
import org.red5.server.api.service.IPendingServiceCall;
import org.red5.server.api.service.IPendingServiceCallback;
import org.red5.server.api.service.IServiceCall;
import org.red5.server.api.service.IServiceCapableConnection;
import org.red5.server.api.stream.IClientBroadcastStream;
import org.red5.server.api.stream.IClientStream;
import org.red5.server.api.stream.IPlaylistSubscriberStream;
import org.red5.server.api.stream.ISingleItemSubscriberStream;
import org.red5.server.api.stream.IStreamCapableConnection;
import org.red5.server.api.stream.IStreamService;
import org.red5.server.exception.ClientRejectedException;
import org.red5.server.net.protocol.RTMPDecodeState;
import org.red5.server.net.rtmp.codec.RTMP;
import org.red5.server.net.rtmp.event.BytesRead;
import org.red5.server.net.rtmp.event.ClientBW;
import org.red5.server.net.rtmp.event.ClientInvokeEvent;
import org.red5.server.net.rtmp.event.ClientNotifyEvent;
import org.red5.server.net.rtmp.event.Invoke;
import org.red5.server.net.rtmp.event.Notify;
import org.red5.server.net.rtmp.event.Ping;
import org.red5.server.net.rtmp.event.ServerBW;
import org.red5.server.net.rtmp.event.VideoData;
import org.red5.server.net.rtmp.message.Constants;
import org.red5.server.net.rtmp.message.Header;
import org.red5.server.net.rtmp.message.Packet;
import org.red5.server.net.rtmp.status.Status;
import org.red5.server.service.Call;
import org.red5.server.service.PendingCall;
import org.red5.server.so.FlexSharedObjectMessage;
import org.red5.server.so.ISharedObjectEvent;
import org.red5.server.so.SharedObjectMessage;
import org.red5.server.stream.ClientBroadcastStream;
import org.red5.server.stream.OutputStream;
import org.red5.server.stream.PlaylistSubscriberStream;
import org.red5.server.stream.SingleItemSubscriberStream;
import org.red5.server.stream.StreamService;
import org.red5.server.util.ScopeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureCallback;
import org.springframework.util.concurrent.ListenableFutureTask;

/**
 * RTMP connection. Stores information about client streams, data transfer channels, pending RPC calls, bandwidth configuration, 
 * AMF encoding type (AMF0/AMF3), connection state (is alive, last ping time and ping result) and session.
 */
public abstract class RTMPConnection extends BaseConnection implements IStreamCapableConnection, IServiceCapableConnection {

	private static Logger log = LoggerFactory.getLogger(RTMPConnection.class);

	public static final String RTMP_SESSION_ID = "rtmp.sessionid";

	public static final String RTMP_HANDSHAKE = "rtmp.handshake";

	/**
	 * Marker byte for standard or non-encrypted RTMP data.
	 */
	public static final byte RTMP_NON_ENCRYPTED = (byte) 0x03;

	/**
	 * Marker byte for encrypted RTMP data.
	 */
	public static final byte RTMP_ENCRYPTED = (byte) 0x06;

	/**
	 * Cipher for RTMPE input
	 */
	public static final String RTMPE_CIPHER_IN = "rtmpe.cipher.in";

	/**
	 * Cipher for RTMPE output
	 */
	public static final String RTMPE_CIPHER_OUT = "rtmpe.cipher.out";

	/**
	 * Connection channels
	 * 
	 * @see org.red5.server.net.rtmp.Channel
	 */
	private transient ConcurrentMap<Integer, Channel> channels = new ConcurrentHashMap<Integer, Channel>(3, 0.9f, 1);

	/**
	 * Client streams
	 * 
	 * @see org.red5.server.api.stream.IClientStream
	 */
	private transient ConcurrentMap<Integer, IClientStream> streams = new ConcurrentHashMap<Integer, IClientStream>(1, 0.9f, 1);

	/**
	 * Reserved stream ids. Stream id's directly relate to individual NetStream instances.
	 */
	private volatile BitSet reservedStreams = new BitSet();

	/**
	 * Transaction identifier for remote commands.
	 */
	private AtomicInteger transactionId = new AtomicInteger(1);

	/**
	 * Hash map that stores pending calls and ids as pairs.
	 */
	private transient ConcurrentMap<Integer, IPendingServiceCall> pendingCalls = new ConcurrentHashMap<Integer, IPendingServiceCall>(3, 0.75f, 1);

	/**
	 * Deferred results set.
	 * 
	 * @see org.red5.server.net.rtmp.DeferredResult
	 */
	private transient CopyOnWriteArraySet<DeferredResult> deferredResults = new CopyOnWriteArraySet<DeferredResult>();

	/**
	 * Last ping round trip time
	 */
	private AtomicInteger lastPingRoundTripTime = new AtomicInteger(-1);

	/**
	 * Timestamp when last ping command was sent.
	 */
	private AtomicLong lastPingSentOn = new AtomicLong(0);

	/**
	 * Timestamp when last ping result was received.
	 */
	private AtomicLong lastPongReceivedOn = new AtomicLong(0);

	/**
	 * RTMP events handler
	 */
	protected transient IRTMPHandler handler;

	/**
	 * Ping interval in ms to detect dead clients.
	 */
	private volatile int pingInterval = 5000;

	/**
	 * Maximum time in ms after which a client is disconnected because of inactivity.
	 */
	protected volatile int maxInactivity = 60000;

	/**
	 * Data read interval
	 */
	protected long bytesReadInterval = 1024 * 1024;

	/**
	 * Number of bytes to read next.
	 */
	protected long nextBytesRead = 1024 * 1024;

	/**
	 * Number of bytes the client reported to have received.
	 */
	private AtomicLong clientBytesRead = new AtomicLong(0L);

	/**
	 * Map for pending video packets and stream IDs.
	 */
	private transient ConcurrentMap<Integer, AtomicInteger> pendingVideos = new ConcurrentHashMap<Integer, AtomicInteger>(1, 0.9f, 1);

	/**
	 * Number of (NetStream) streams used.
	 */
	private AtomicInteger usedStreams = new AtomicInteger(0);

	/**
	 * Remembered stream buffer durations.
	 */
	private transient ConcurrentMap<Integer, Integer> streamBuffers = new ConcurrentHashMap<Integer, Integer>(1, 0.9f, 1);

	/**
	 * Maximum time in milliseconds to wait for a valid handshake.
	 */
	private int maxHandshakeTimeout = 5000;

	/**
	 * Maximum time in milliseconds allowed to process received message
	 */
	protected long maxHandlingTimeout = 500L;

	/**
	 * Bandwidth limit type / enforcement. (0=hard,1=soft,2=dynamic)
	 */
	protected int limitType = 0;

	/**
	 * Protocol state
	 */
	protected RTMP state = new RTMP();

	// protection for the decoder when using multiple threads per connection
	protected transient Semaphore decoderLock = new Semaphore(1, true);

	// protection for the encoder when using multiple threads per connection
	protected transient Semaphore encoderLock = new Semaphore(1, true);

	// keeps track of the decode state
	protected transient ThreadLocal<RTMPDecodeState> decoderState = new ThreadLocal<RTMPDecodeState>() {

		@Override
		protected RTMPDecodeState initialValue() {
			return new RTMPDecodeState(getSessionId());
		}

	};

	/**
	 * Scheduling service
	 */
	protected transient ThreadPoolTaskScheduler scheduler;

	/**
	 * Thread pool for message handling.
	 */
	protected transient ThreadPoolTaskExecutor executor;

	/**
	 * Thread pool for guarding deadlocks.
	 */
	protected transient ThreadPoolTaskScheduler deadlockGuardScheduler;

	/**
	 * Keep-alive worker flag
	 */
	protected final AtomicBoolean running;

	/**
	 * Timestamp generator
	 */
	private final AtomicInteger timer = new AtomicInteger(0);

	/**
	 * Closing flag
	 */
	private final AtomicBoolean closing = new AtomicBoolean(false);

	/**
	 * Packet sequence number
	 * */
	private AtomicLong packetSequence = new AtomicLong();

	/**
	 * Specify the size of queue that will trigger audio packet dropping, disabled if it's 0
	 * */
	private Integer executorQueueSizeToDropAudioPackets = 0;

	/**
	 * Keep track of current queue size
	 * */
	private AtomicInteger currentQueueSize = new AtomicInteger();

	/**
	 * Wait for handshake task.
	 */
	private ScheduledFuture<?> waitForHandshakeTask;
	
	/**
	 * Keep alive task.
	 */
	private ScheduledFuture<?> keepAliveTask;

	/**
	 * Creates anonymous RTMP connection without scope.
	 * 
	 * @param type
	 *            Connection type
	 */
	@ConstructorProperties({ "type" })
	public RTMPConnection(String type) {
		// We start with an anonymous connection without a scope.
		// These parameters will be set during the call of "connect" later.
		super(type);
		// set running flag
		running = new AtomicBoolean(false);
	}

	public int getId() {
		// handle the fact that a client id is a String
		return client != null ? client.getId().hashCode() : -1;
	}

	@Deprecated
	public void setId(int clientId) {
		log.warn("Setting of a client id is deprecated, use IClient to manipulate the id", new Exception("RTMPConnection.setId is deprecated"));
	}

	public void setHandler(IRTMPHandler handler) {
		this.handler = handler;
	}

	public RTMP getState() {
		return state;
	}

	public byte getStateCode() {
		return state.getState();
	}

	public void setStateCode(byte code) {
		if (log.isTraceEnabled()) {
			log.trace("setStateCode: {} - {}", code, RTMP.states[code]);
		}
		state.setState(code);
	}

	/**
	 * @return the decoderLock
	 */
	public Semaphore getDecoderLock() {
		return decoderLock;
	}

	/**
	 * @return the decoderLock
	 */
	public Semaphore getEncoderLock() {
		return encoderLock;
	}

	/**
	 * @return the decoderState
	 */
	public RTMPDecodeState getDecoderState() {
		return decoderState.get();
	}

	/** {@inheritDoc} */
	public void setBandwidth(int mbits) {
		// tell the flash player how fast we want data and how fast we shall send it
		getChannel(2).write(new ServerBW(mbits));
		// second param is the limit type (0=hard,1=soft,2=dynamic)
		getChannel(2).write(new ClientBW(mbits, (byte) limitType));
	}

	/**
	 * Returns a usable timestamp for written packets.
	 * 
	 * @return timestamp
	 */
	public int getTimer() {
		return timer.incrementAndGet();
	}

	/**
	 * Opens the connection.
	 */
	public void open() {
		if (log.isTraceEnabled()) {
			// dump memory stats
			log.trace("Memory at open - free: {}K total: {}K", Runtime.getRuntime().freeMemory() / 1024, Runtime.getRuntime().totalMemory() / 1024);
		}
	}

	@Override
	public boolean connect(IScope newScope, Object[] params) {
		if (log.isDebugEnabled()) {
			log.debug("Connect scope: {}", newScope);
		}
		try {
			boolean success = super.connect(newScope, params);
			if (success) {
				stopWaitForHandshake();
				// once the handshake has completed, start needed jobs start the ping / pong keep-alive
				startRoundTripMeasurement();
			} else {
				if (log.isDebugEnabled()) {
					log.debug("Connect failed");
				}
			}
			return success;
		} catch (ClientRejectedException e) {
			String reason = (String) e.getReason();
			log.info("Client rejected, reason: " + ((reason != null) ? reason : "None"));
			stopWaitForHandshake();
			throw e;
		}
	}

	/**
	 * Start waiting for a valid handshake.
	 */
	public void startWaitForHandshake() {
		if (log.isDebugEnabled()) {
			log.debug("startWaitForHandshake - {}", sessionId);
		}
		// start the handshake checker after maxHandshakeTimeout milliseconds
		try {
			waitForHandshakeTask = scheduler.schedule(new WaitForHandshakeTask(),
					new Date(System.currentTimeMillis() + maxHandshakeTimeout));
		} catch (TaskRejectedException e) {
			log.error("WaitForHandshake task was rejected for " + sessionId, e);
		}
	}

	/**
	 * Cancels wait for handshake task.
	 */
	private void stopWaitForHandshake() {
		if (waitForHandshakeTask != null) {
			boolean cancelled = waitForHandshakeTask.cancel(true);
			if (cancelled) {
				log.debug("waitForHandshake was cancelled for {}", sessionId);
			}
			waitForHandshakeTask = null;
		}
	}

	/**
	 * Starts measurement.
	 */
	private void startRoundTripMeasurement() {
		if (scheduler != null) {
			if (pingInterval > 0) {
				if (log.isDebugEnabled()) {
					log.debug("startRoundTripMeasurement - {}", sessionId);
				}
				try {
					keepAliveTask = scheduler.scheduleAtFixedRate(new KeepAliveTask(), pingInterval);
					if (log.isDebugEnabled()) {
						log.debug("Keep alive scheduled for: {}", sessionId);
					}
				} catch (Exception e) {
					log.error("Error creating keep alive job for: " + sessionId, e);
				}
			}
		} else {
			log.error("startRoundTripMeasurement cannot be executed due to missing scheduler. This can happen if a connection drops before handshake is complete");
		}
	}

	/**
	 * Stops measurement.
	 */
	private void stopRoundTripMeasurement() {
		if (keepAliveTask != null) {
			boolean cancelled = keepAliveTask.cancel(true);
			if (cancelled) {
				log.debug("Keep alive was cancelled for {}", sessionId);
			}
			keepAliveTask = null;
		}
	}

	/**
	 * Initialize connection.
	 * 
	 * @param host
	 *            Connection host
	 * @param path
	 *            Connection path
	 * @param params
	 *            Params passed from client
	 */
	public void setup(String host, String path, Map<String, Object> params) {
		this.host = host;
		this.path = path;
		this.params = params;
		if (Integer.valueOf(3).equals(params.get("objectEncoding"))) {
			if (log.isDebugEnabled()) {
				log.debug("Setting object encoding to AMF3");
			}
			state.setEncoding(Encoding.AMF3);
		}
	}

	/**
	 * Return AMF protocol encoding used by this connection.
	 * 
	 * @return AMF encoding used by connection
	 */
	public Encoding getEncoding() {
		return state.getEncoding();
	}

	/**
	 * Getter for next available channel id.
	 * 
	 * @return Next available channel id
	 */
	public int getNextAvailableChannelId() {
		int result = 4;
		while (isChannelUsed(result)) {
			result++;
		}
		return result;
	}

	/**
	 * Checks whether channel is used.
	 * 
	 * @param channelId
	 *            Channel id
	 * @return <pre>true</pre> if channel is in use, <pre>false</pre> otherwise
	 */
	public boolean isChannelUsed(int channelId) {
		return channels.get(channelId) != null;
	}

	/**
	 * Return channel by id.
	 * 
	 * @param channelId
	 *            Channel id
	 * @return Channel by id
	 */
	public Channel getChannel(int channelId) {
		if (channels != null) {
			Channel channel = channels.putIfAbsent(channelId, new Channel(this, channelId));
			if (channel == null) {
				channel = channels.get(channelId);
			}
			return channel;
		} else {
			return new Channel(null, channelId);
		}
	}

	/**
	 * Closes channel.
	 * 
	 * @param channelId
	 *            Channel id
	 */
	public void closeChannel(int channelId) {
		Channel chan = channels.remove(channelId);
		if (log.isDebugEnabled()) {
			log.debug("Closing / removing channel: {}", chan);
		}
		chan = null;
	}

	/**
	 * Getter for client streams.
	 * 
	 * @return Client streams as array
	 */
	protected Collection<IClientStream> getStreams() {
		return streams.values();
	}

	/** {@inheritDoc} */
	public int reserveStreamId() {
		int result = -1;
		for (int i = 0; true; i++) {
			if (!reservedStreams.get(i)) {
				reservedStreams.set(i);
				result = i;
				break;
			}
		}
		return result + 1;
	}

	/** {@inheritDoc} */
	public int reserveStreamId(int id) {
		int result = -1;
		if (!reservedStreams.get(id - 1)) {
			reservedStreams.set(id - 1);
			result = id - 1;
		} else {
			result = reserveStreamId();
		}
		return result;
	}

	/**
	 * Returns whether or not a given stream id is valid.
	 * 
	 * @param streamId stream id
	 * @return true if its valid, false if its invalid
	 */
	public boolean isValidStreamId(int streamId) {
		int index = streamId - 1;
		if (index < 0 || !reservedStreams.get(index)) {
			// stream id has not been reserved before
			return false;
		}
		if (streams.get(streamId - 1) != null) {
			// another stream already exists with this id
			return false;
		}
		return true;
	}

	/**
	 * Returns whether or not the connection has been idle for a maximum period.
	 * 
	 * @return true if max idle period has been exceeded, false otherwise
	 */
	public boolean isIdle() {
		long lastPingTime = lastPingSentOn.get();
		long lastPongTime = lastPongReceivedOn.get();
		boolean idle = (lastPongTime > 0 && (lastPingTime - lastPongTime > maxInactivity));
		if (log.isTraceEnabled()) {
			log.trace("Connection {} {} idle", getSessionId(), (idle ? "is" : "is not"));
		}
		return idle;
	}

	/**
	 * Returns whether or not the connection is disconnected.
	 * 
	 * @return true if connection state is RTMP.STATE_DISCONNECTED, false otherwise
	 */
	public boolean isDisconnected() {
		return state.getState() == RTMP.STATE_DISCONNECTED;
	}

	/**
	 * Creates output stream object from stream id. Output stream consists of audio, data and video channels.
	 * 
	 * @see org.red5.server.stream.OutputStream
	 * 
	 * @param streamId
	 *            Stream id
	 * @return Output stream object
	 */
	public OutputStream createOutputStream(int streamId) {
		int channelId = (4 + ((streamId - 1) * 5));
		if (log.isDebugEnabled()) {
			log.debug("Channel id range start: {}", channelId);
		}
		final Channel data = getChannel(channelId++);
		final Channel video = getChannel(channelId++);
		final Channel audio = getChannel(channelId++);
		return new OutputStream(video, audio, data);
	}

	/** {@inheritDoc} */
	public IClientBroadcastStream newBroadcastStream(int streamId) {
		if (isValidStreamId(streamId)) {
			// get ClientBroadcastStream defined as a prototype in red5-common.xml
			ClientBroadcastStream cbs = (ClientBroadcastStream) scope.getContext().getBean("clientBroadcastStream");
			Integer buffer = streamBuffers.get(streamId - 1);
			if (buffer != null) {
				cbs.setClientBufferDuration(buffer);
			}
			cbs.setStreamId(streamId);
			cbs.setConnection(this);
			cbs.setName(createStreamName());
			cbs.setScope(this.getScope());

			registerStream(cbs);
			usedStreams.incrementAndGet();
			return cbs;
		}
		return null;
	}

	/** {@inheritDoc} */
	public ISingleItemSubscriberStream newSingleItemSubscriberStream(int streamId) {
		if (isValidStreamId(streamId)) {
			// get SingleItemSubscriberStream defined as a prototype in red5-common.xml
			SingleItemSubscriberStream siss = (SingleItemSubscriberStream) scope.getContext().getBean("singleItemSubscriberStream");
			Integer buffer = streamBuffers.get(streamId - 1);
			if (buffer != null) {
				siss.setClientBufferDuration(buffer);
			}
			siss.setName(createStreamName());
			siss.setConnection(this);
			siss.setScope(this.getScope());
			siss.setStreamId(streamId);
			registerStream(siss);
			usedStreams.incrementAndGet();
			return siss;
		}
		return null;
	}

	/** {@inheritDoc} */
	public IPlaylistSubscriberStream newPlaylistSubscriberStream(int streamId) {
		if (isValidStreamId(streamId)) {
			// get PlaylistSubscriberStream defined as a prototype in red5-common.xml
			PlaylistSubscriberStream pss = (PlaylistSubscriberStream) scope.getContext().getBean("playlistSubscriberStream");
			Integer buffer = streamBuffers.get(streamId - 1);
			if (buffer != null) {
				pss.setClientBufferDuration(buffer);
			}
			pss.setName(createStreamName());
			pss.setConnection(this);
			pss.setScope(this.getScope());
			pss.setStreamId(streamId);
			registerStream(pss);
			usedStreams.incrementAndGet();
			return pss;
		}
		return null;
	}

	public void addClientStream(IClientStream stream) {
		int streamIndex = stream.getStreamId() - 1;
		if (!reservedStreams.get(streamIndex)) {
			reservedStreams.set(streamIndex);
			streams.put(streamIndex, stream);
			usedStreams.incrementAndGet();
		}
	}

	public void removeClientStream(int streamId) {
		unreserveStreamId(streamId);
	}

	/**
	 * Getter for used stream count.
	 * 
	 * @return Value for property 'usedStreamCount'.
	 */
	protected int getUsedStreamCount() {
		return usedStreams.get();
	}

	/** {@inheritDoc} */
	public IClientStream getStreamById(int id) {
		if (id <= 0) {
			return null;
		}
		return streams.get(id - 1);
	}

	/**
	 * Return stream id for given channel id.
	 * 
	 * @param channelId
	 *            Channel id
	 * @return ID of stream that channel belongs to
	 */
	public int getStreamIdForChannel(int channelId) {
		if (channelId < 4) {
			return 0;
		}
		return ((channelId - 4) / 5) + 1;
	}

	/**
	 * Return stream by given channel id.
	 * 
	 * @param channelId
	 *            Channel id
	 * @return Stream that channel belongs to
	 */
	public IClientStream getStreamByChannelId(int channelId) {
		if (channelId < 4) {
			return null;
		}
		return streams.get(getStreamIdForChannel(channelId) - 1);
	}

	/**
	 * Store a stream in the connection.
	 * 
	 * @param stream
	 */
	private void registerStream(IClientStream stream) {
		streams.put(stream.getStreamId() - 1, stream);
	}

	/**
	 * Remove a stream from the connection.
	 * 
	 * @param stream
	 */
	@SuppressWarnings("unused")
	private void unregisterStream(IClientStream stream) {
		streams.remove(stream.getStreamId());
	}

	/** {@inheritDoc} */
	@Override
	public void close() {
		if (closing.compareAndSet(false, true)) {
			if (log.isDebugEnabled()) {
				log.debug("close: {}", sessionId);
			}
			stopWaitForHandshake();
			stopRoundTripMeasurement();
			// update our state
			if (state != null) {
				final byte s = getStateCode();
				switch (s) {
					case RTMP.STATE_DISCONNECTED:
						if (log.isDebugEnabled()) {
							log.debug("Already disconnected");
						}
						return;
					default:
						if (log.isDebugEnabled()) {
							log.debug("State: {}", RTMP.states[s]);
						}
						state.setState(RTMP.STATE_DISCONNECTING);
				}
			}
			Red5.setConnectionLocal(this);
			IStreamService streamService = (IStreamService) ScopeUtils.getScopeService(scope, IStreamService.class, StreamService.class);
			if (streamService != null) {
				for (Map.Entry<Integer, IClientStream> entry : streams.entrySet()) {
					IClientStream stream = entry.getValue();
					if (stream != null) {
						if (log.isDebugEnabled()) {
							log.debug("Closing stream: {}", stream.getStreamId());
						}
						streamService.deleteStream(this, stream.getStreamId());
						usedStreams.decrementAndGet();
					}
				}
			} else {
				if (log.isDebugEnabled()) {
					log.debug("Stream service was not found for scope: {}", (scope != null ? scope.getName() : "null or non-existant"));
				}
			}
			// close the base connection - disconnect scopes and unregister client
			super.close();
			// kill all the collections etc
			if (channels != null) {
				channels.clear();
			} else {
				if (log.isTraceEnabled()) {
					log.trace("Channels collection was null");
				}
			}
			if (streams != null) {
				streams.clear();
			} else {
				if (log.isTraceEnabled()) {
					log.trace("Streams collection was null");
				}
			}
			if (pendingCalls != null) {
				pendingCalls.clear();
			} else {
				if (log.isTraceEnabled()) {
					log.trace("PendingCalls collection was null");
				}
			}
			if (deferredResults != null) {
				deferredResults.clear();
			} else {
				if (log.isTraceEnabled()) {
					log.trace("DeferredResults collection was null");
				}
			}
			if (pendingVideos != null) {
				pendingVideos.clear();
			} else {
				if (log.isTraceEnabled()) {
					log.trace("PendingVideos collection was null");
				}
			}
			if (streamBuffers != null) {
				streamBuffers.clear();
			} else {
				if (log.isTraceEnabled()) {
					log.trace("StreamBuffers collection was null");
				}
			}
			// drain permits
			decoderLock.drainPermits();
			encoderLock.drainPermits();
			if (log.isTraceEnabled()) {
				// dump memory stats
				log.trace("Memory at close - free: {}K total: {}K", Runtime.getRuntime().freeMemory() / 1024, Runtime.getRuntime().totalMemory() / 1024);
			}
		} else {
			if (log.isDebugEnabled()) {
				log.debug("Already closing..");
			}
		}
	}

	/**
	 * Dispatches event
	 * 
	 * @param event
	 *            Event
	 */
	@Override
	public void dispatchEvent(IEvent event) {
		if (log.isDebugEnabled()) {
			log.debug("Event notify: {}", event);
		}
		// determine if its an outgoing invoke or notify
		switch (event.getType()) {
			case CLIENT_INVOKE:
				ClientInvokeEvent cie = (ClientInvokeEvent) event;
				invoke(cie.getMethod(), cie.getParams(), cie.getCallback());
				break;
			case CLIENT_NOTIFY:
				ClientNotifyEvent cne = (ClientNotifyEvent) event;
				notify(cne.getMethod(), cne.getParams());
				break;
			default:
				log.warn("Unhandled event: {}", event);
		}
	}

	/**
	 * When the connection has been closed, notify any remaining pending service calls that they have failed because the connection is broken. Implementors of IPendingServiceCallback may only deduce from this notification that it was not possible to read a result for this service call. It is possible that (1) the service call was never written to the service, or (2) the service call was written to the service and
	 * although the remote method was invoked, the connection failed before the result could be read, or (3) although the remote method was invoked on the service, the service implementor detected the failure of the connection and performed only partial processing. The caller only knows that it cannot be confirmed that the callee has invoked the service call and returned a result.
	 */
	public void sendPendingServiceCallsCloseError() {
		if (pendingCalls != null && !pendingCalls.isEmpty()) {
			if (log.isDebugEnabled()) {
				log.debug("Connection calls pending: {}", pendingCalls.size());
			}
			for (IPendingServiceCall call : pendingCalls.values()) {
				call.setStatus(Call.STATUS_NOT_CONNECTED);
				for (IPendingServiceCallback callback : call.getCallbacks()) {
					callback.resultReceived(call);
				}
			}
		}
	}

	/** {@inheritDoc} */
	public void unreserveStreamId(int streamId) {
		deleteStreamById(streamId);
		if (streamId > 0) {
			reservedStreams.clear(streamId - 1);
		}
	}

	/** {@inheritDoc} */
	public void deleteStreamById(int streamId) {
		if (streamId > 0) {
			if (streams.get(streamId - 1) != null) {
				pendingVideos.remove(streamId);
				usedStreams.decrementAndGet();
				streams.remove(streamId - 1);
				streamBuffers.remove(streamId - 1);
			}
		}
	}

	/**
	 * Handler for ping event.
	 * 
	 * @param ping
	 *            Ping event context
	 */
	public void ping(Ping ping) {
		getChannel(2).write(ping);
	}

	/**
	 * Write packet.
	 * 
	 * @param out
	 *            Packet
	 */
	public abstract void write(Packet out);

	/**
	 * Write raw byte buffer.
	 * 
	 * @param out
	 *            IoBuffer
	 */
	public abstract void writeRaw(IoBuffer out);

	/**
	 * Update number of bytes to read next value.
	 */
	protected void updateBytesRead() {
		if (log.isTraceEnabled())
			log.trace("updateBytesRead");
		long bytesRead = getReadBytes();
		if (bytesRead >= nextBytesRead) {
			BytesRead sbr = new BytesRead((int) (bytesRead % Integer.MAX_VALUE));
			getChannel(2).write(sbr);
			nextBytesRead += bytesReadInterval;
		}
	}

	/**
	 * Read number of received bytes.
	 * 
	 * @param bytes
	 *            Number of bytes
	 */
	public void receivedBytesRead(int bytes) {
		if (log.isDebugEnabled()) {
			log.debug("Client received {} bytes, written {} bytes, {} messages pending", new Object[] { bytes, getWrittenBytes(), getPendingMessages() });
		}
		clientBytesRead.addAndGet(bytes);
	}

	/**
	 * Get number of bytes the client reported to have received.
	 * 
	 * @return Number of bytes
	 */
	public long getClientBytesRead() {
		return clientBytesRead.get();
	}

	/** {@inheritDoc} */
	public void invoke(IServiceCall call) {
		invoke(call, 3);
	}

	/**
	 * Generate next invoke id.
	 * 
	 * @return Next invoke id for RPC
	 */
	public int getTransactionId() {
		return transactionId.incrementAndGet();
	}

	/**
	 * Register pending call (remote function call that is yet to finish).
	 * 
	 * @param invokeId
	 *            Deferred operation id
	 * @param call
	 *            Call service
	 */
	public void registerPendingCall(int invokeId, IPendingServiceCall call) {
		pendingCalls.put(invokeId, call);
	}

	/** {@inheritDoc} */
	public void invoke(IServiceCall call, int channel) {
		// We need to use Invoke for all calls to the client
		Invoke invoke = new Invoke();
		invoke.setCall(call);
		invoke.setTransactionId(getTransactionId());
		if (call instanceof IPendingServiceCall) {
			registerPendingCall(invoke.getTransactionId(), (IPendingServiceCall) call);
		}
		getChannel(channel).write(invoke);
	}

	/** {@inheritDoc} */
	public void invoke(String method) {
		invoke(method, null, null);
	}

	/** {@inheritDoc} */
	public void invoke(String method, Object[] params) {
		invoke(method, params, null);
	}

	/** {@inheritDoc} */
	public void invoke(String method, IPendingServiceCallback callback) {
		invoke(method, null, callback);
	}

	/** {@inheritDoc} */
	public void invoke(String method, Object[] params, IPendingServiceCallback callback) {
		IPendingServiceCall call = new PendingCall(method, params);
		if (callback != null) {
			call.registerCallback(callback);
		}
		invoke(call);
	}

	/** {@inheritDoc} */
	public void notify(IServiceCall call) {
		notify(call, 3);
	}

	/** {@inheritDoc} */
	public void notify(IServiceCall call, int channel) {
		Notify notify = new Notify();
		notify.setCall(call);
		getChannel(channel).write(notify);
	}

	/** {@inheritDoc} */
	public void notify(String method) {
		notify(method, null);
	}

	/** {@inheritDoc} */
	public void notify(String method, Object[] params) {
		IServiceCall call = new Call(method, params);
		notify(call);
	}

	/** {@inheritDoc} */
	public void status(Status status) {
		status(status, 3);
	}

	/** {@inheritDoc} */
	public void status(Status status, int channel) {
		if (status != null) {
			getChannel(channel).sendStatus(status);
		}
	}

	/** {@inheritDoc} */
	@Override
	public long getReadBytes() {
		return 0;
	}

	/** {@inheritDoc} */
	@Override
	public long getWrittenBytes() {
		return 0;
	}

	/**
	 * Get pending call service by id.
	 * 
	 * @param invokeId
	 *            Pending call service id
	 * @return Pending call service object
	 */
	public IPendingServiceCall getPendingCall(int invokeId) {
		return pendingCalls.get(invokeId);
	}

	/**
	 * Retrieves and removes the pending call service by id.
	 * 
	 * @param invokeId
	 *            Pending call service id
	 * @return Pending call service object
	 */
	public IPendingServiceCall retrievePendingCall(int invokeId) {
		return pendingCalls.remove(invokeId);
	}

	/**
	 * Generates new stream name.
	 * 
	 * @return New stream name
	 */
	protected String createStreamName() {
		return UUID.randomUUID().toString();
	}

	/**
	 * Mark message as being written.
	 * 
	 * @param message
	 *            Message to mark
	 */
	protected void writingMessage(Packet message) {
		if (message.getMessage() instanceof VideoData) {
			int streamId = message.getHeader().getStreamId();
			final AtomicInteger value = new AtomicInteger();
			AtomicInteger old = pendingVideos.putIfAbsent(streamId, value);
			if (old == null) {
				old = value;
			}
			old.incrementAndGet();
		}
	}

	/**
	 * Increases number of read messages by one. Updates number of bytes read.
	 */
	public void messageReceived() {
		if (log.isTraceEnabled()) {
			log.trace("messageReceived");
		}
		readMessages.incrementAndGet();
		// trigger generation of BytesRead messages
		updateBytesRead();
	}

	private String getMessageType(Packet packet) {
		final Header header = packet.getHeader();
		final byte headerDataType = header.getDataType();
		return messageTypeToName(headerDataType);
	}

	public String messageTypeToName(byte headerDataType) {
		switch (headerDataType) {
			case Constants.TYPE_AGGREGATE:
				return "TYPE_AGGREGATE";
			case Constants.TYPE_AUDIO_DATA:
				return "TYPE_AUDIO_DATA";
			case Constants.TYPE_VIDEO_DATA:
				return "TYPE_VIDEO_DATA";
			case Constants.TYPE_FLEX_SHARED_OBJECT:
				return "TYPE_FLEX_SHARED_OBJECT";
			case Constants.TYPE_SHARED_OBJECT:
				return "TYPE_SHARED_OBJECT";
			case Constants.TYPE_INVOKE:
				return "TYPE_INVOKE";
			case Constants.TYPE_FLEX_MESSAGE:
				return "TYPE_FLEX_MESSAGE";
			case Constants.TYPE_NOTIFY:
				return "TYPE_NOTIFY";
			case Constants.TYPE_FLEX_STREAM_SEND:
				return "TYPE_FLEX_STREAM_SEND";
			case Constants.TYPE_PING:
				return "TYPE_PING";
			case Constants.TYPE_BYTES_READ:
				return "TYPE_BYTES_READ";
			case Constants.TYPE_CHUNK_SIZE:
				return "TYPE_CHUNK_SIZE";
			case Constants.TYPE_CLIENT_BANDWIDTH:
				return "TYPE_CLIENT_BANDWIDTH";
			case Constants.TYPE_SERVER_BANDWIDTH:
				return "TYPE_SERVER_BANDWIDTH";
			default:
				return "UNKNOWN [" + headerDataType + "]";

		}
	}

	/**
	 * Handle the incoming message.
	 * 
	 * @param message message
	 */
	@SuppressWarnings("unchecked")
	public void handleMessageReceived(Packet message) {
		if (log.isTraceEnabled()) {
			log.trace("handleMessageReceived - {}", sessionId);
		}
		final byte dataType = message.getHeader().getDataType();
		// route these types outside the executor
		switch (dataType) {
			case Constants.TYPE_PING:
			case Constants.TYPE_ABORT:
			case Constants.TYPE_BYTES_READ:
			case Constants.TYPE_CHUNK_SIZE:
			case Constants.TYPE_CLIENT_BANDWIDTH:
			case Constants.TYPE_SERVER_BANDWIDTH:
				// pass message to the handler
				try {
					handler.messageReceived(this, message);
				} catch (Exception e) {
					log.error("Error processing received message {}", sessionId, e);
				}
				break;
			default:
				if (executor != null) {
					try {
						final long packetNumber = packetSequence.incrementAndGet();

						if (executorQueueSizeToDropAudioPackets > 0 && currentQueueSize.get() >= executorQueueSizeToDropAudioPackets) {
							if (message.getHeader().getDataType() == Constants.TYPE_AUDIO_DATA) {
								/**
								 * There's a backlog of messages in the queue. Flash might have sent a burst of messages after a network congestion. Throw away packets that we are able to discard.
								 */
								log.info("Queue threshold reached. Discarding packet: session=[{}], msgType=[{}], packetNum=[{}]", getSessionId(), getMessageType(message), packetNumber);
								return;
							}
						}
						ReceivedMessageTask task = new ReceivedMessageTask(sessionId, message, handler, this);
						task.setMaxHandlingTimeout(maxHandlingTimeout);
						packetSequence.incrementAndGet();
						final Packet sentMessage = message;
						final long startTime = System.currentTimeMillis();
						ListenableFuture<Boolean> future = (ListenableFuture<Boolean>) executor.submitListenable(new ListenableFutureTask<Boolean>(task));
						currentQueueSize.incrementAndGet();
						future.addCallback(new ListenableFutureCallback<Boolean>() {

							private int getProcessingTime() {
								return (int) (System.currentTimeMillis() - startTime);
							}

							public void onFailure(Throwable t) {
								currentQueueSize.decrementAndGet();
								if (log.isWarnEnabled()) {
									log.warn("onFailure - session: {}, msgtype: {}, processingTime: {}, packetNum: {}", sessionId, getMessageType(sentMessage), getProcessingTime(), packetNumber);
								}
							}

							public void onSuccess(Boolean success) {
								currentQueueSize.decrementAndGet();
								if (log.isDebugEnabled()) {
									log.debug("onSuccess - session: {}, msgType: {}, processingTime: {}, packetNum: {}", sessionId, getMessageType(sentMessage), getProcessingTime(), packetNumber);
								}
							}

						});
					} catch (TaskRejectedException tre) {
						Throwable[] suppressed = tre.getSuppressed();
						for (Throwable t : suppressed) {
							log.warn("Suppressed exception on {}", sessionId, t);
						}
						log.info("Rejected message: {} on {}", message, sessionId);
					} catch (Exception e) {
						log.info("Incoming message handling failed on session=[{}], messageType=[{}]", getSessionId(), message);
						if (log.isDebugEnabled()) {
							log.debug("Execution rejected on {} - {}", getSessionId(), RTMP.states[getStateCode()]);
							log.debug("Lock permits - decode: {} encode: {}", decoderLock.availablePermits(), encoderLock.availablePermits());
						}
					}
				} else {
					log.warn("Executor is null on {} state: {}", getSessionId(), RTMP.states[getStateCode()]);
				}
		}
	}

	/**
	 * Mark message as sent.
	 * 
	 * @param message
	 *            Message to mark
	 */
	public void messageSent(Packet message) {
		if (message.getMessage() instanceof VideoData) {
			int streamId = message.getHeader().getStreamId();
			AtomicInteger pending = pendingVideos.get(streamId);
			if (log.isTraceEnabled()) {
				log.trace("Stream id: {} pending: {} total pending videos: {}", streamId, pending, pendingVideos.size());
			}
			if (pending != null) {
				pending.decrementAndGet();
			}
		}
		writtenMessages.incrementAndGet();
	}

	/**
	 * Increases number of dropped messages.
	 */
	protected void messageDropped() {
		droppedMessages.incrementAndGet();
	}

	/** {@inheritDoc} */
	@Override
	public long getPendingVideoMessages(int streamId) {
		if (log.isTraceEnabled()) {
			log.trace("Total pending videos: {}", pendingVideos.size());
		}
		AtomicInteger count = pendingVideos.get(streamId);
		long result = (count != null ? count.intValue() - getUsedStreamCount() : 0);
		return (result > 0 ? result : 0);
	}

	/**
	 * Send a shared object message.
	 * 
	 * @param name shared object name
	 * @param currentVersion the current version
	 * @param persistent toggle
	 * @param events shared object events
	 */
	public void sendSharedObjectMessage(String name, int currentVersion, boolean persistent, ConcurrentLinkedQueue<ISharedObjectEvent> events) {
		// create a new sync message for every client to avoid concurrent access through multiple threads
		SharedObjectMessage syncMessage = state.getEncoding() == Encoding.AMF3 ? new FlexSharedObjectMessage(null, name, currentVersion, persistent) : new SharedObjectMessage(null, name, currentVersion, persistent);
		syncMessage.addEvents(events);
		try {
			// get the channel for so updates
			Channel channel = getChannel((byte) 3);
			if (log.isTraceEnabled()) {
				log.trace("Send to channel: {}", channel);
			}
			channel.write(syncMessage);
		} catch (Exception e) {
			log.warn("Exception sending shared object", e);
		}
	}

	/** {@inheritDoc} */
	public void ping() {
		long newPingTime = System.currentTimeMillis();
		if (log.isDebugEnabled()) {
			log.debug("Send Ping: session=[{}], currentTime=[{}], lastPingTime=[{}]", new Object[] { getSessionId(), newPingTime, lastPingSentOn.get() });
		}
		if (lastPingSentOn.get() == 0) {
			lastPongReceivedOn.set(newPingTime);
		}
		Ping pingRequest = new Ping();
		pingRequest.setEventType(Ping.PING_CLIENT);
		lastPingSentOn.set(newPingTime);
		int now = (int) (newPingTime & 0xffffffff);
		pingRequest.setValue2(now);
		ping(pingRequest);
	}

	/**
	 * Marks that ping back was received.
	 * 
	 * @param pong
	 *            Ping object
	 */
	public void pingReceived(Ping pong) {
		long now = System.currentTimeMillis();
		long previousPingValue = (int) (lastPingSentOn.get() & 0xffffffff);
		if (log.isDebugEnabled()) {
			log.debug("Pong Rx: session=[{}] at {} with value {}, previous received at {}", new Object[] { getSessionId(), now, pong.getValue2(), previousPingValue });
		}
		if (pong.getValue2() == previousPingValue) {
			lastPingRoundTripTime.set((int) (now & 0xffffffff) - pong.getValue2());
			if (log.isDebugEnabled()) {
				log.debug("Ping response session=[{}], RTT=[{} ms]", new Object[] { getSessionId(), lastPingRoundTripTime.get() });
			}
		} else {
			// don't log the congestion entry unless there are more than X messages waiting
			if (getPendingMessages() > 4) {
				int pingRtt = (int) (now & 0xffffffff) - pong.getValue2();
				log.info("Pong delayed: session=[{}], ping response took [{} ms] to arrive. Connection may be congested, or loopback", new Object[] { getSessionId(), pingRtt });
			}
		}
		lastPongReceivedOn.set(now);
	}

	/**
	 * Difference between when the last ping was sent and when the last pong was received.
	 * 
	 * @return last interval of ping minus pong
	 */
	public int getLastPingSentAndLastPongReceivedInterval() {
	       return (int) (lastPingSentOn.get() - lastPongReceivedOn.get());
	}
	
	/** {@inheritDoc} */
	public int getLastPingTime() {
		return lastPingRoundTripTime.get();
	}

	/**
	 * Setter for ping interval.
	 * 
	 * @param pingInterval
	 *            Interval in ms to ping clients. Set to <pre>0</pre> to disable ghost detection code.
	 */
	public void setPingInterval(int pingInterval) {
		this.pingInterval = pingInterval;
	}

	/**
	 * Setter for maximum inactivity.
	 * 
	 * @param maxInactivity
	 *            Maximum time in ms after which a client is disconnected in case of inactivity.
	 */
	public void setMaxInactivity(int maxInactivity) {
		this.maxInactivity = maxInactivity;
	}

	/**
	 * Inactive state event handler.
	 */
	protected abstract void onInactive();

	/**
	 * Sets the scheduler.
	 * 
	 * @param scheduler
	 *            scheduling service / thread executor
	 */
	public void setScheduler(ThreadPoolTaskScheduler scheduler) {
		this.scheduler = scheduler;
	}

	/**
	 * @return the scheduler
	 */
	public ThreadPoolTaskScheduler getScheduler() {
		return scheduler;
	}

	public ThreadPoolTaskExecutor getExecutor() {
		return executor;
	}

	public void setExecutor(ThreadPoolTaskExecutor executor) {
		this.executor = executor;
	}

	/**
	 * Thread pool for guarding deadlocks
	 *
	 * @return the deadlockGuardScheduler
	 */
	public ThreadPoolTaskScheduler getDeadlockGuardScheduler() {
		return deadlockGuardScheduler;
	}

	/**
	 * Thread pool for guarding deadlocks
	 * 
	 * @param deadlockGuardScheduler the deadlockGuardScheduler to set
	 */
	public void setDeadlockGuardScheduler(ThreadPoolTaskScheduler deadlockGuardScheduler) {
		this.deadlockGuardScheduler = deadlockGuardScheduler;
	}

	/**
	 * Registers deferred result.
	 * 
	 * @param result
	 *            Result to register
	 */
	public void registerDeferredResult(DeferredResult result) {
		deferredResults.add(result);
	}

	/**
	 * Unregister deferred result
	 * 
	 * @param result
	 *            Result to unregister
	 */
	public void unregisterDeferredResult(DeferredResult result) {
		deferredResults.remove(result);
	}

	public void rememberStreamBufferDuration(int streamId, int bufferDuration) {
		streamBuffers.put(streamId - 1, bufferDuration);
	}

	/**
	 * Set maximum time to wait for valid handshake in milliseconds.
	 * 
	 * @param maxHandshakeTimeout
	 *            Maximum time in milliseconds
	 */
	public void setMaxHandshakeTimeout(int maxHandshakeTimeout) {
		this.maxHandshakeTimeout = maxHandshakeTimeout;
	}

	public long getMaxHandlingTimeout() {
		return maxHandlingTimeout;
	}

	public void setMaxHandlingTimeout(long maxHandlingTimeout) {
		this.maxHandlingTimeout = maxHandlingTimeout;
	}

	/** {@inheritDoc} */
	@Override
	public String toString() {
		if (log.isDebugEnabled()) {
			String id = getClient() != null ? getClient().getId() : null;
			return String.format("%1$s %2$s:%3$s to %4$s client: %5$s session: %6$s state: %7$s", new Object[] { getClass().getSimpleName(), getRemoteAddress(), getRemotePort(), getHost(), id, getSessionId(), RTMP.states[getStateCode()] });
		} else {
			Object[] args = new Object[] { getClass().getSimpleName(), getRemoteAddress(), getReadBytes(), getWrittenBytes(), getSessionId(), RTMP.states[getStateCode()] };
			return String.format("%1$s from %2$s (in: %3$s out: %4$s) session: %5$s state: %6$s", args);
		}
	}

	/**
	 * Specify the size of queue that will trigger audio packet dropping, disabled if it's 0
	 * 
	 * @param executorQueueSizeToDropAudioPackets queue size
	 */
	public void setExecutorQueueSizeToDropAudioPackets(Integer executorQueueSizeToDropAudioPackets) {
		this.executorQueueSizeToDropAudioPackets = executorQueueSizeToDropAudioPackets;
	}

	/**
	 * Task that keeps connection alive and disconnects if client is dead.
	 */
	private class KeepAliveTask implements Runnable {

		private final AtomicLong lastBytesRead = new AtomicLong(0);

		private volatile long lastBytesReadTime = 0;

		public void run() {
			// we dont ping until in connected state
			if (state.getState() == RTMP.STATE_CONNECTED) {
				// ensure the job is not already running
				if (running.compareAndSet(false, true)) {
					if (log.isTraceEnabled())
						log.trace("Running keep-alive for {}", getSessionId());
					try {
						// first check connected
						if (isConnected()) {
							// get now
							long now = System.currentTimeMillis();
							// get the current bytes read count on the connection
							long currentReadBytes = getReadBytes();
							// get our last bytes read count
							long previousReadBytes = lastBytesRead.get();
							if (log.isTraceEnabled()) {
								log.trace("Time now: {} current read count: {} last read count: {}", new Object[] { now, currentReadBytes, previousReadBytes });
							}
							if (currentReadBytes > previousReadBytes) {
								if (log.isTraceEnabled()) {
									log.trace("Client is still alive, no ping needed");
								}
								// client has sent data since last check and thus is not dead. No need to ping
								if (lastBytesRead.compareAndSet(previousReadBytes, currentReadBytes)) {
									// update the timestamp to match our update
									lastBytesReadTime = now;
								}
								// check idle
								if (isIdle()) {
									onInactive();
								}
							} else {
								// client didn't send response to ping command and didn't sent data for too long, disconnect
								long lastPingTime = lastPingSentOn.get();
								long lastPongTime = lastPongReceivedOn.get();
								if (lastPongTime > 0 && (lastPingTime - lastPongTime > maxInactivity) && (now - lastBytesReadTime > maxInactivity)) {
									log.warn("Closing connection - inactivity timeout: session=[{}], lastPongReceived=[{} ms ago], lastPingSent=[{} ms ago], lastDataRx=[{} ms ago]", new Object[] { getSessionId(), (lastPingTime - lastPongTime), (now - lastPingTime), (now - lastBytesReadTime) });
									// the following line deals with a very common support request
									log.warn("Client on session=[{}] has not responded to our ping for [{} ms] and we haven't received data for [{} ms]", new Object[] { getSessionId(), (lastPingTime - lastPongTime), (now - lastBytesReadTime) });
									onInactive();
								} else {
									// send ping command to client to trigger sending of data
									ping();
								}
							}
						} else {
							if (log.isDebugEnabled()) {
								log.debug("No longer connected, clean up connection. Connection state: {}", RTMP.states[state.getState()]);
							}
							onInactive();
						}
					} catch (Exception e) {
						log.warn("Exception in keepalive for {}", getSessionId(), e);
					} finally {
						// reset running flag
						running.compareAndSet(true, false);
					}
				}
			}
		}
	}

	/**
	 * Task that waits for a valid handshake and disconnects the client if none is received.
	 */
	private class WaitForHandshakeTask implements Runnable {

		public WaitForHandshakeTask() {
			log.trace("Scheduler: {}", scheduler);
			if (log.isTraceEnabled()) {
				log.trace("WaitForHandshakeTask created for {}", getSessionId());
			}
		}

		public void run() {
			if (log.isTraceEnabled()) {
				log.trace("WaitForHandshakeTask started for {}", getSessionId());
			}
			// check for connected state before disconnecting
			if (state.getState() != RTMP.STATE_CONNECTED) {
				// Client didn't send a valid handshake, disconnect
				log.warn("Closing {}, due to long handshake. State: {}", getSessionId(), RTMP.states[getStateCode()]);
				onInactive();
			}
		}

	}

}
