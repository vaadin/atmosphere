package com.google.appengine.api.channel;

import java.io.IOException;

/**
 *
 * @author p.havelaar
 */
public interface ChannelService {

    public String createChannel(String string);

    public String createChannel(String string, int i);

    public void sendMessage(ChannelMessage cm);

    public ChannelMessage parseMessage(javax.servlet.http.HttpServletRequest hsr);

    public ChannelPresence parsePresence(javax.servlet.http.HttpServletRequest hsr) throws IOException;    
}
