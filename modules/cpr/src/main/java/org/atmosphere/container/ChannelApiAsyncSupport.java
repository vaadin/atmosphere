package org.atmosphere.container;

import com.google.appengine.api.channel.ChannelMessage;
import com.google.appengine.api.channel.ChannelPresence;
import com.google.appengine.api.channel.ChannelService;
import com.google.appengine.api.channel.ChannelServiceFactory;
import java.io.IOException;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework.AtmosphereHandlerWrapper;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceFactory;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.util.SimpleBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TODO Chunked messages if output buffer gets large
 * 
 * @author p.havelaar
 */
public class ChannelApiAsyncSupport extends AsynchronousProcessor {
    
    private final Logger logger = LoggerFactory.getLogger(ChannelApiAsyncSupport.class.getName());
    
    private AtmosphereHandler presenceHandler = new AtmosphereHandler() {
        public void onRequest(AtmosphereResource ar) throws IOException {
        }
        public void onStateChange(AtmosphereResourceEvent are) throws IOException {
        }
        public void destroy() {
        }
    };

    public ChannelApiAsyncSupport(AtmosphereConfig config) {
        super(config);
        String broadcasterClassName = SimpleBroadcaster.class.getName();
        logger.info("Setting default broadcaster classname: " + broadcasterClassName);
        config.framework().setDefaultBroadcasterClassName(broadcasterClassName);
        config.framework().setBroadcasterFactory(null);
        for (AtmosphereHandlerWrapper wr : config.framework().getAtmosphereHandlers().values()) {
            if (wr.broadcaster != null 
                    && DefaultBroadcaster.class.isAssignableFrom(wr.broadcaster.getClass())
                    && wr.broadcaster.getID().equals(wr.mapping)) {
                logger.info("Overriding default broadcaster of handler with path " + wr.mapping 
                        + " with " + broadcasterClassName);
                wr.broadcaster.destroy();
                wr.broadcaster = config.framework().getBroadcasterFactory().get(wr.mapping);
            }
        }
    }

    @Override
    public void init(ServletConfig sc) throws ServletException {
        super.init(sc);
        Broadcaster presenceBroadcaster = new SimpleBroadcaster("ChannelAPIPresenceBroadcaster", config);
        config.framework().addAtmosphereHandler("/_ah/channel/connected/", presenceHandler, presenceBroadcaster);
        config.framework().addAtmosphereHandler("/_ah/channel/disconnected/", presenceHandler, presenceBroadcaster);
    }


    public String getContainerName() {
        return super.getContainerName() + " using channel API";
    }

    public Action service(AtmosphereRequest req, AtmosphereResponse resp) throws IOException, ServletException {
        if (isChannelPresence(req)) {
            handleChannelPresence(req);
            return Action.RESUME;
        } else {
            Action action = suspended(req, resp);
            if (action.equals(Action.SUSPEND)) {
                String token = ChannelServiceFactory.getChannelService().createChannel(req.resource().uuid());
                if (token == null) {
                    throw new IllegalStateException("Failed to create channel for request: " + req.resource().uuid());
                } else {
                    logger.debug("Suspending resource " + req.resource().uuid() + " with token " + token);
                }
                resp.setHeader("chapi_token", token);
            }
            return action;
        }
    }

    @Override
    public void action(AtmosphereResourceImpl res) {
        super.action(res);
        
        if (res.action().equals(Action.RESUME)) {
            resume(res);
        } else if (res.action().equals(Action.SUSPEND)) {
            throw new UnsupportedOperationException("not implemented");
        }
    }

    protected void resume(AtmosphereResource res) {
        if (isAlive(res) && res.isSuspended()) {
            sendToClient(res.uuid(), "disconnect;");
        }
    }
    
    protected boolean isChannelPresence(HttpServletRequest req) {
        String path = req.getServletPath() + (req.getPathInfo() != null ? req.getPathInfo() : "");
        return req.getMethod().equals("POST") &&
            (path.startsWith("/_ah/channel/connected/")
            || path.startsWith("/_ah/channel/disconnected/"));
    }
    
    protected void handleChannelPresence(HttpServletRequest req) throws IOException {
        ChannelService channelService = ChannelServiceFactory.getChannelService();
        ChannelPresence presence = channelService.parsePresence(req);
        if (presence != null) {
            logger.debug("Detected channel presense notification connected:"+ presence.isConnected()
                    + " id:"+ presence.clientId());
            AtmosphereResource resource = AtmosphereResourceFactory.find(presence.clientId());
            if (presence.isConnected()) {
                if (resource != null) {
                    connected(resource, presence);
                    ChannelApiAsyncSupport.sendConnected(presence.clientId());
                } else {
                    throw new IllegalStateException("Failed to find atmosphere resource for: " + presence.clientId());
                }
            } else {
                if (resource != null) {
                    disconnected(resource, presence);
                } else {
                    logger.warn("Failed to find resource for disconnecting channel: " + presence.clientId());
                }
            }
        }
    }
    
            
    protected void connected(AtmosphereResource resource, ChannelPresence presence) {
        // TODO we need to sort out which http request/response to use for the atmosphereresource
         
        final String uuid = presence.clientId();
        resource.getResponse().setResponse(new HttpServletResponseWrapper(
                (HttpServletResponse) resource.getResponse().getResponse()) {
            ServletOutputStream out = new ServletOutputStream() {
                private StringBuilder buffer = new StringBuilder();
                @Override
                public void flush() throws IOException {
                    if (buffer.length() > 0) {
                        ChannelApiAsyncSupport.sendMessage(uuid, buffer.toString());
                        buffer.setLength(0);
                    }
                }
                @Override
                public void write(int b) throws IOException {
                    buffer.append(String.valueOf((char)b));
                }
                @Override
                public void write(byte[] b) throws IOException {
                    buffer.append(new String(b, getCharacterEncoding()));
                }
                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    buffer.append(new String(b, off, len, getCharacterEncoding()));
                }
            };
            @Override
            public ServletOutputStream getOutputStream() throws IOException {
                return out;
            }

        });
    }

    protected void disconnected(AtmosphereResource resource, ChannelPresence presence) {
        resource.getRequest().setRequest(null);
        resource.getResponse().setResponse(null);
        resource.resume();
    }
    
    static boolean isAlive(AtmosphereResource res) {
        return res.getResponse().getResponse() != null;
    }
    
    static void sendConnected(String uuid) {
        sendToClient(uuid, "connected:id=" + uuid + ";");
    }
    static void sendMessage(String uuid, String message) {
        sendToClient(uuid, "message:l=" + message.length() + ";" + message);
    }
    private static void sendToClient(String uuid, String message) {
        ChannelService channelService = ChannelServiceFactory.getChannelService();
        channelService.sendMessage(
                new ChannelMessage(uuid, message));
    }
}
