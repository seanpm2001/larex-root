/*
 * Copyright (c) 2010-2010 the original author or authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.codehaus.larex.io.connector;

import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;

import org.codehaus.larex.io.RuntimeSocketConnectException;

/**
 * Responsibility: to create and operate on {@link SocketChannel}s.
 *
 * @version $Revision: 903 $ $Date$
 */
public interface ClientConnector
{
    public void connect(InetSocketAddress address) throws RuntimeSocketConnectException;

    public void close();

    boolean awaitClosed(long timeout) throws InterruptedException;
}