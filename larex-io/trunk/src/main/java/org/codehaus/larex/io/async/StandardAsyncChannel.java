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

package org.codehaus.larex.io.async;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

import org.codehaus.larex.io.ByteBuffers;
import org.codehaus.larex.io.RuntimeIOException;
import org.codehaus.larex.io.RuntimeSocketClosedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @version $Revision: 903 $ $Date$
 */
public class StandardAsyncChannel implements AsyncChannel
{
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final SocketChannel channel;
    private final AsyncCoordinator coordinator;
    private final ByteBuffers byteBuffers;
    private volatile int readAggressiveness = 2;
    private volatile int writeAggressiveness = 2;
    private volatile SelectionKey selectionKey;
    private Thread writer;

    public StandardAsyncChannel(SocketChannel channel, AsyncCoordinator coordinator, ByteBuffers byteBuffers)
    {
        this.channel = channel;
        this.coordinator = coordinator;
        this.byteBuffers = byteBuffers;
    }

    public int getReadAggressiveness()
    {
        return readAggressiveness;
    }

    public void setReadAggressiveness(int readAggressiveness)
    {
        this.readAggressiveness = readAggressiveness;
    }

    public int getWriteAggressiveness()
    {
        return writeAggressiveness;
    }

    public void setWriteAggressiveness(int writeAggressiveness)
    {
        this.writeAggressiveness = writeAggressiveness;
    }

    public void register(Selector selector, AsyncSelector.Listener listener) throws RuntimeSocketClosedException
    {
	    try
		{
	        selectionKey = channel.register(selector, 0, listener);
        }
        catch (ClosedChannelException x)
        {
            throw new RuntimeSocketClosedException(x);
        }
    }

    public void update(int operations, boolean add) throws RuntimeSocketClosedException
    {
        try
        {
            int oldOperations = selectionKey.interestOps();
            if (add)
                selectionKey.interestOps(oldOperations | operations);
            else
                selectionKey.interestOps(oldOperations & ~operations);
            int newOperations = selectionKey.interestOps();
            logger.debug("Channel {} operations {} -> {}", new Object[]{this, oldOperations, newOperations});
        }
        catch (CancelledKeyException x)
        {
            throw new RuntimeSocketClosedException(x);
        }
    }

    public void read(int readBufferSize) throws RuntimeSocketClosedException
    {
        try
        {
            int read;
            ByteBuffer buffer = byteBuffers.acquire(readBufferSize, false);
            try
            {
                int start = buffer.position();
                boolean closed = readAggressively(channel, buffer);
                read = buffer.position() - start;
                logger.debug("Channel {} read {} bytes into {}", new Object[]{this, read, buffer});

                if (read > 0)
                {
                    buffer.flip();
                    coordinator.onRead(buffer);
                    if (closed)
                        coordinator.onClose();
                }
            }
            finally
            {
                byteBuffers.release(buffer);
            }

            if (read == 0)
            {
                if (channel.isOpen())
                {
                    // We read 0 bytes, we need to re-register for read interest
                    coordinator.needsRead(true);
                }
                else
                {
                    throw new ClosedChannelException();
                }
            }
        }
        catch (ClosedChannelException x)
        {
            logger.debug("Channel closed during read");
            close();
            throw new RuntimeSocketClosedException(x);
        }
        catch (IOException x)
        {
            logger.debug("Unexpected IOException", x);
            close();
            throw new RuntimeIOException(x);
        }
    }

    protected boolean readAggressively(SocketChannel channel, ByteBuffer buffer) throws IOException
    {
        int readAggressiveness = this.readAggressiveness;
        for (int aggressiveness = 0; aggressiveness < readAggressiveness; ++aggressiveness)
        {
            int read = channel.read(buffer);
            if (read < 0)
                return true;
        }
        return false;
    }

    public void write(ByteBuffer buffer) throws RuntimeSocketClosedException
    {
        try
        {
            while (buffer.hasRemaining())
            {
                int written = writeAggressively(channel, buffer);
                logger.debug("Channel {} wrote {} bytes from {}", new Object[]{this, written, buffer});

                if (buffer.hasRemaining())
                {
                    // We could not write everything, suspend the writer thread until we are write ready
                    synchronized (this)
                    {
                        // We must issue the needsWrite() below within the sync block, otherwise
                        // another thread can issue a notify that no one is ready to listen and
                        // this thread will wait forever for a notify that already happened.

                        // We wrote less bytes then expected, register for write interest
                        coordinator.needsWrite(true);

                        assert writer == null;
                        writer = Thread.currentThread();

                        while (writer != null)
                        {
                            logger.debug("Writer thread {} suspended on partial write, {} bytes remaining", writer, buffer.remaining());
                            wait();
                        }
                        logger.debug("Writer thread {} resumed, {} bytes remaining", writer, buffer.remaining());
                    }
                }
            }
        }
        catch (InterruptedException x)
        {
            logger.debug("Interrupted during pending write");
            close();
            Thread.currentThread().interrupt();
            throw new RuntimeSocketClosedException(new ClosedByInterruptException());
        }
        catch (ClosedChannelException x)
        {
            logger.debug("Channel closed during write of {} bytes", buffer.remaining());
            close();
            throw new RuntimeSocketClosedException(x);
        }
        catch (IOException x)
        {
            logger.debug("Unexpected IOException", x);
            close();
            throw new RuntimeIOException(x);
        }
    }

    public void writeReady()
    {
        // Notify writing thread
        synchronized (this)
        {
            if (writer != null)
            {
                logger.debug("Write ready, signaling writer thread {}", writer);
                writer = null;
                notify();
            }
        }
    }

    protected int writeAggressively(SocketChannel channel, ByteBuffer buffer) throws IOException
    {
        int writeAggressiveness = this.writeAggressiveness;
        int result = 0;
        for (int aggressiveness = 0; aggressiveness < writeAggressiveness; ++aggressiveness)
        {
            result += this.channel.write(buffer);
        }
        return result;
    }

    public void close()
    {
        if (!channel.isOpen())
            return;

        try
        {
            if (selectionKey != null)
                selectionKey.cancel();

            logger.debug("Channel {} closing", this);
            channel.close();
        }
        catch (IOException x)
        {
            throw new RuntimeIOException(x);
        }
    }

    @Override
    public String toString()
    {
        return channel.toString();
    }
}