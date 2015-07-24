/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011, Red Hat, Inc. and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.mobicents.media.core.endpoints;

import java.util.concurrent.atomic.AtomicInteger;

import org.mobicents.media.core.connections.AbstractConnection;
import org.mobicents.media.server.component.audio.AudioSplitter;
import org.mobicents.media.server.component.audio.MixerComponent;
import org.mobicents.media.server.component.oob.OOBSplitter;
import org.mobicents.media.server.concurrent.ConcurrentMap;
import org.mobicents.media.server.spi.Connection;
import org.mobicents.media.server.spi.ConnectionMode;
import org.mobicents.media.server.spi.ConnectionType;
import org.mobicents.media.server.spi.ResourceUnavailableException;

/**
 * Basic implementation of the endpoint.
 * 
 * @author yulian oifa
 * @author amit bhayani
 */
public class BaseSplitterEndpoint extends AbstractEndpoint {

    // Media splitters
    protected AudioSplitter audioSplitter;
    protected OOBSplitter oobSplitter;

    // Media splitter components
    private final ConcurrentMap<MixerComponent> mediaComponents;

    private AtomicInteger loopbackCount = new AtomicInteger(0);
    private AtomicInteger readCount = new AtomicInteger(0);
    private AtomicInteger writeCount = new AtomicInteger(0);

    public BaseSplitterEndpoint(String localName) {
        super(localName);
        this.mediaComponents = new ConcurrentMap<MixerComponent>(2);
    }

    @Override
    public void start() throws ResourceUnavailableException {
        super.start();
        audioSplitter = new AudioSplitter(getScheduler());
        oobSplitter = new OOBSplitter(getScheduler());
    }

    @Override
    public Connection createConnection(ConnectionType type, Boolean isLocal) throws ResourceUnavailableException {
        AbstractConnection connection = (AbstractConnection) super.createConnection(type, isLocal);

        // Retrieve and register the mixer component of the connection
        MixerComponent mediaComponent = connection.getMediaComponent("audio");
        this.mediaComponents.put(connection.getId(), mediaComponent);

        // Add media component to the media splitter
        switch (type) {
            case RTP:
                audioSplitter.addOutsideComponent(mediaComponent.getAudioComponent());
                oobSplitter.addOutsideComponent(mediaComponent.getOOBComponent());
                break;

            case LOCAL:
                audioSplitter.addInsideComponent(mediaComponent.getAudioComponent());
                oobSplitter.addInsideComponent(mediaComponent.getOOBComponent());
                break;
        }
        return connection;
    }

    @Override
    public void deleteConnection(Connection connection, ConnectionType connectionType) {
        // Release the connection
        super.deleteConnection(connection, connectionType);

        // Unregister the media component of the connection
        MixerComponent mediaComponent = this.mediaComponents.remove(connection.getId());

        // Release the media component from the media splitter
        switch (connectionType) {
            case RTP:
                audioSplitter.releaseOutsideComponent(mediaComponent.getAudioComponent());
                oobSplitter.releaseOutsideComponent(mediaComponent.getOOBComponent());
                break;

            case LOCAL:
                audioSplitter.releaseInsideComponent(mediaComponent.getAudioComponent());
                oobSplitter.releaseInsideComponent(mediaComponent.getOOBComponent());
                break;
        }
    }

    @Override
    public void modeUpdated(ConnectionMode oldMode, ConnectionMode newMode) {
        int readCount = 0, loopbackCount = 0, writeCount = 0;
        switch (oldMode) {
            case RECV_ONLY:
                readCount -= 1;
                break;
            case SEND_ONLY:
                writeCount -= 1;
                break;
            case SEND_RECV:
            case CONFERENCE:
                readCount -= 1;
                writeCount -= 1;
                break;
            case NETWORK_LOOPBACK:
                loopbackCount -= 1;
                break;
            default:
                // XXX handle default case
                break;
        }

        switch (newMode) {
            case RECV_ONLY:
                readCount += 1;
                break;
            case SEND_ONLY:
                writeCount += 1;
                break;
            case SEND_RECV:
            case CONFERENCE:
                readCount += 1;
                writeCount += 1;
                break;
            case NETWORK_LOOPBACK:
                loopbackCount += 1;
                break;
            default:
                // XXX handle default case
                break;
        }

        if (readCount != 0 || writeCount != 0 || loopbackCount != 0) {
            // something changed
            loopbackCount = this.loopbackCount.addAndGet(loopbackCount);
            readCount = this.readCount.addAndGet(readCount);
            writeCount = this.writeCount.addAndGet(writeCount);

            if (loopbackCount > 0 || readCount == 0 || writeCount == 0) {
                audioSplitter.stop();
                oobSplitter.stop();
            } else {
                audioSplitter.start();
                oobSplitter.start();
            }
        }
    }
}
