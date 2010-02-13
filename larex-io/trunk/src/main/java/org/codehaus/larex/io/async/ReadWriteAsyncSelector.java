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
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.codehaus.larex.io.RuntimeIOException;
import org.codehaus.larex.io.RuntimeSocketClosedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @version $Revision: 903 $ $Date$
 */
public class ReadWriteAsyncSelector implements AsyncSelector
{
    private static final AtomicInteger ids = new AtomicInteger();

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<Runnable>();
    private final Selector selector;
    private final Thread thread;

    public ReadWriteAsyncSelector()
    {
        this(new ThreadFactory()
        {
            public Thread newThread(Runnable r)
            {
                Thread thread = new Thread(r);
                thread.setName(AsyncSelector.class.getSimpleName() + "-" + ids.incrementAndGet());
                return thread;
            }
        });
    }

    public ReadWriteAsyncSelector(ThreadFactory threadFactory)
    {
        try
        {
            this.selector = Selector.open();
            this.thread = threadFactory.newThread(new SelectorLoop());
            this.thread.start();
        }
        catch (IOException x)
        {
            throw new RuntimeIOException(x);
        }
    }

    public void register(AsyncChannel channel, Listener listener)
    {
        tasks.add(new Register(selector, channel, listener));
        wakeup();
    }

    public void update(AsyncChannel channel, int operations, boolean add)
    {
        Update task = new Update(channel, operations, add);
        if (Thread.currentThread() == thread)
        {
            task.run();
        }
        else
        {
            tasks.add(task);
            wakeup();
        }
    }

    public void wakeup()
    {
        selector.wakeup();
    }

    public void close()
    {
        tasks.add(new Close());
        wakeup();
    }

    public boolean join(long timeout) throws InterruptedException
    {
        thread.join(timeout);
        return !thread.isAlive();
    }

    protected void processTasks()
    {
        while (tasks.size() > 0)
        {
            Runnable task = tasks.poll();
            logger.debug("Processing task {}", task);
            task.run();
        }
    }

    protected void process(SelectionKey selectedKey) throws IOException
    {
        if (selectedKey.isReadable())
        {
            Listener listener = (Listener)selectedKey.attachment();
            listener.readReady();
        }
        else if (selectedKey.isWritable())
        {
            Listener listener = (Listener)selectedKey.attachment();
            listener.writeReady();
        }
    }

    private class Register implements Runnable
    {
        private final Selector selector;
        private final AsyncChannel channel;
        private final Listener listener;

        private Register(Selector selector, AsyncChannel channel, Listener listener)
        {
            this.selector = selector;
            this.channel = channel;
            this.listener = listener;
        }

        public void run()
        {
            try
            {
                channel.register(selector, listener);
                listener.open();
            }
            catch (RuntimeSocketClosedException x)
            {
                logger.debug("Ignoring registration of listener {} for closed channel {}", listener, channel);
            }
        }
    }

    private class Update implements Runnable
    {
        private final AsyncChannel channel;
        private final int operations;
        private final boolean add;

        public Update(AsyncChannel channel, int operations, boolean add)
        {
            this.channel = channel;
            this.operations = operations;
            this.add = add;
        }

        public void run()
        {
            try
            {
                channel.update(operations, add);
            }
            catch (RuntimeSocketClosedException x)
            {
                logger.debug("Ignoring update for closed channel {}", channel);
            }
        }
    }

    private class Close implements Runnable
    {
        public void run()
        {
            for (SelectionKey key : selector.keys())
            {
                Listener listener = (Listener)key.attachment();
                listener.close();
            }

            try
            {
                selector.close();
            }
            catch (IOException x)
            {
                throw new RuntimeIOException(x);
            }
        }
    }

    private class SelectorLoop implements Runnable
    {
        public void run()
        {
            try
            {
                logger.debug("Selector loop entered");

                while (selector.isOpen())
                {
                    try
                    {
                        processTasks();

                        logger.debug("Selector loop waiting on select");
                        int selected = selector.select();
                        logger.debug("Selector loop woken up from select, {}/{} selected", selected, selector.keys().size());

                        // Closing the selector causes a wakeup, check if we have to exit
                        if (!selector.isOpen())
                            break;

                        if (selected > 0)
                        {
                            Set<SelectionKey> selectedKeys = selector.selectedKeys();
                            for (Iterator<SelectionKey> iterator = selectedKeys.iterator(); iterator.hasNext();)
                            {
                                SelectionKey selectedKey = iterator.next();
                                logger.debug("Selector loop selected key {} with operations {}", selectedKey, selectedKey.interestOps());
                                iterator.remove();

                                if (!selectedKey.isValid())
                                {
                                    logger.debug("Ignoring invalid key {}", selectedKey);
                                    continue;
                                }

                                process(selectedKey);
                            }
                        }
                    }
                    catch (ClosedSelectorException x)
                    {
                        break;
                    }
                    catch (IOException x)
                    {
                        close();
                        throw new RuntimeIOException(x);
                    }
                }
            }
            finally
            {
                logger.info("Selector loop exited");
            }
        }
    }
}