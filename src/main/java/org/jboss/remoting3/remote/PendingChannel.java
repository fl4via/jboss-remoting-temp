/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
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

package org.jboss.remoting3.remote;

import org.jboss.remoting3.Channel;
import org.xnio.Result;

/**
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
final class PendingChannel {
    private final int id;
    private final int outboundWindowSize;
    private final int inboundWindowSize;
    private final int outboundMessageCount;
    private final int inboundMessageCount;
    private final Result<Channel> result;

    PendingChannel(final int id, final int outboundWindowSize, final int inboundWindowSize, final int outboundMessageCount, final int inboundMessageCount, final Result<Channel> result) {
        this.id = id;
        this.outboundWindowSize = outboundWindowSize;
        this.inboundWindowSize = inboundWindowSize;
        this.outboundMessageCount = outboundMessageCount;
        this.inboundMessageCount = inboundMessageCount;
        this.result = result;
    }

    int getId() {
        return id;
    }

    int getOutboundWindowSize() {
        return outboundWindowSize;
    }

    int getInboundWindowSize() {
        return inboundWindowSize;
    }

    int getOutboundMessageCount() {
        return outboundMessageCount;
    }

    int getInboundMessageCount() {
        return inboundMessageCount;
    }

    Result<Channel> getResult() {
        return result;
    }

    static final IntIndexer<PendingChannel> INDEXER = new IntIndexer<PendingChannel>() {
        public int indexOf(final PendingChannel argument) {
            return argument.id;
        }

        public boolean equals(final PendingChannel argument, final int index) {
            return argument.id == index;
        }
    };
}
