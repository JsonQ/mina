/*
 *   @(#) $Id$
 *
 *   Copyright 2004 The Apache Software Foundation
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */
package org.apache.mina.io.datagram;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.mina.common.ByteBuffer;
import org.apache.mina.io.IoAcceptor;
import org.apache.mina.io.DefaultExceptionMonitor;
import org.apache.mina.io.ExceptionMonitor;
import org.apache.mina.io.IoHandler;
import org.apache.mina.io.IoHandlerFilter;
import org.apache.mina.util.IoHandlerFilterManager;
import org.apache.mina.util.Queue;

/**
 * {@link IoAcceptor} for datagram transport (UDP/IP).
 * 
 * @author Trustin Lee (trustin@apache.org)
 * @version $Rev$, $Date$
 */
public class DatagramAcceptor extends DatagramProcessor implements IoAcceptor
{
    private static volatile int nextId = 0;

    private final IoHandlerFilterManager filterManager = new IoHandlerFilterManager();

    private final int id = nextId ++ ;

    private final Selector selector;

    private final Map channels = new HashMap();

    private final Queue registerQueue = new Queue();

    private final Queue cancelQueue = new Queue();

    private final Queue flushingSessions = new Queue();

    private ExceptionMonitor exceptionMonitor = new DefaultExceptionMonitor();

    private Worker worker;

    /**
     * Creates a new instance.
     * 
     * @throws IOException
     */
    public DatagramAcceptor() throws IOException
    {
        selector = Selector.open();
    }

    public void bind( SocketAddress address, IoHandler handler )
            throws IOException
    {
        if( address == null )
            throw new NullPointerException( "address" );
        if( handler == null )
            throw new NullPointerException( "handler" );

        if( !( address instanceof InetSocketAddress ) )
            throw new IllegalArgumentException( "Unexpected address type: "
                                                + address.getClass() );

        DatagramChannel ch = DatagramChannel.open();
        boolean bound = false;
        try
        {
            ch.configureBlocking( false );
            ch.socket().bind( address );
            bound = true;
        }
        finally
        {
            if( !bound )
            {
                ch.close();
            }
        }

        synchronized( this )
        {
            synchronized( registerQueue )
            {
                registerQueue.push( new RegistrationRequest( ch, handler ) );
            }
            channels.put( address, ch );

            if( worker == null )
            {
                worker = new Worker();
                worker.start();
            }
        }

        selector.wakeup();
    }

    public void unbind( SocketAddress address )
    {
        if( address == null )
            throw new NullPointerException( "address" );

        DatagramChannel ch;

        synchronized( this )
        {
            ch = ( DatagramChannel ) channels.get( address );

            if( ch == null )
                throw new IllegalArgumentException( "Unknown address: "
                                                    + address );

            SelectionKey key = ch.keyFor( selector );
            channels.remove( address );
            synchronized( cancelQueue )
            {
                cancelQueue.push( key );
            }
        }

        selector.wakeup();
        ch.socket().close();
    }

    public void flushSession( DatagramSession session )
    {
        scheduleFlush( session );
        selector.wakeup();
    }

    public void closeSession( DatagramSession session )
    {
    }

    private void scheduleFlush( DatagramSession session )
    {
        synchronized( flushingSessions )
        {
            flushingSessions.push( session );
        }
    }

    private class Worker extends Thread
    {
        public Worker()
        {
            super( "DatagramAcceptor-" + id );
        }

        public void run()
        {
            for( ;; )
            {
                try
                {
                    int nKeys = selector.select();

                    registerNew();

                    if( selector.keys().isEmpty() )
                    {
                        synchronized( DatagramAcceptor.this )
                        {
                            if( selector.keys().isEmpty() )
                            {
                                worker = null;
                                break;
                            }
                        }
                    }

                    if( nKeys > 0 )
                    {
                        processReadySessions( selector.selectedKeys() );
                    }

                    flushSessions();
                    cancelKeys();
                }
                catch( IOException e )
                {
                    exceptionMonitor.exceptionCaught( DatagramAcceptor.this,
                            e );

                    try
                    {
                        Thread.sleep( 1000 );
                    }
                    catch( InterruptedException e1 )
                    {
                    }
                }
            }
        }
    }

    private void processReadySessions( Set keys )
    {
        Iterator it = keys.iterator();
        while( it.hasNext() )
        {
            SelectionKey key = ( SelectionKey ) it.next();
            it.remove();

            DatagramChannel ch = ( DatagramChannel ) key.channel();

            DatagramSession session = new DatagramSession(
                    DatagramAcceptor.this, filterManager, ch,
                    ( IoHandler ) key.attachment() );
            session.setSelectionKey( key );

            if( key.isReadable() )
            {
                readSession( session );
            }

            if( key.isWritable() )
            {
                scheduleFlush( session );
            }
        }
    }

    private void readSession( DatagramSession session )
    {

        ByteBuffer readBuf = ByteBuffer.allocate( 2048 );
        try
        {
            SocketAddress remoteAddress = session.getChannel().receive(
                    readBuf.buf() );
            if( remoteAddress != null )
            {
                readBuf.flip();
                session.setRemoteAddress( remoteAddress );

                ByteBuffer newBuf = ByteBuffer.allocate( readBuf.limit() );
                newBuf.put( readBuf );
                newBuf.flip();

                session.increaseReadBytes( newBuf.remaining() );
                filterManager.fireDataRead( session, newBuf );
            }
        }
        catch( IOException e )
        {
            filterManager.fireExceptionCaught( session, e );
        }
        finally
        {
            ByteBuffer.release( readBuf );
        }
    }

    private void flushSessions()
    {
        if( flushingSessions.size() == 0 )
            return;

        for( ;; )
        {
            DatagramSession session;

            synchronized( flushingSessions )
            {
                session = ( DatagramSession ) flushingSessions.pop();
            }

            if( session == null )
                break;

            try
            {
                flush( session );
            }
            catch( IOException e )
            {
                session.getFilterManager().fireExceptionCaught( session, e );
            }
        }
    }

    private void flush( DatagramSession session ) throws IOException
    {
        DatagramChannel ch = session.getChannel();

        Queue writeBufferQueue = session.getWriteBufferQueue();
        Queue writeMarkerQueue = session.getWriteMarkerQueue();

        ByteBuffer buf;
        Object marker;
        for( ;; )
        {
            synchronized( writeBufferQueue )
            {
                buf = ( ByteBuffer ) writeBufferQueue.first();
                marker = writeMarkerQueue.first();
            }

            if( buf == null )
                break;

            if( buf.remaining() == 0 )
            {
                // pop and fire event
                synchronized( writeBufferQueue )
                {
                    writeBufferQueue.pop();
                    writeMarkerQueue.pop();
                }

                try
                {
                    ByteBuffer.release( buf );
                }
                catch( IllegalStateException e )
                {
                    session.getFilterManager().fireExceptionCaught( session,
                            e );
                }

                session.getFilterManager().fireDataWritten( session, marker );
                continue;
            }

            int writtenBytes = ch
                    .send( buf.buf(), session.getRemoteAddress() );

            SelectionKey key = session.getSelectionKey();
            if( writtenBytes == 0 )
            {
                // Kernel buffer is full
                key.interestOps( key.interestOps() | SelectionKey.OP_WRITE );
            }
            else
            {
                key.interestOps( key.interestOps()
                                 & ( ~SelectionKey.OP_WRITE ) );

                // pop and fire event
                synchronized( writeBufferQueue )
                {
                    writeBufferQueue.pop();
                    writeMarkerQueue.pop();
                }

                session.increaseWrittenBytes( writtenBytes );
                session.getFilterManager().fireDataWritten( session, marker );
            }
        }
    }

    private void registerNew() throws ClosedChannelException
    {
        if( registerQueue.isEmpty() )
            return;

        for( ;; )
        {
            RegistrationRequest req;
            synchronized( registerQueue )
            {
                req = ( RegistrationRequest ) registerQueue.pop();
            }

            if( req == null )
                break;

            req.channel
                    .register( selector, SelectionKey.OP_READ, req.handler );
        }
    }

    private void cancelKeys()
    {
        if( cancelQueue.isEmpty() )
            return;

        for( ;; )
        {
            SelectionKey key;
            synchronized( cancelQueue )
            {
                key = ( SelectionKey ) cancelQueue.pop();
            }

            if( key == null )
                break;
            else
            {
                key.cancel();
                selector.wakeup(); // wake up again to trigger thread death
            }
        }
    }

    public void addFilter( int priority, IoHandlerFilter filter )
    {
        filterManager.addFilter( priority, filter );
    }

    public void removeFilter( IoHandlerFilter filter )
    {
        filterManager.removeFilter( filter );
    }

    public void removeAllFilters()
    {
        filterManager.removeAllFilters();
    }

    public List getAllFilters()
    {
        return filterManager.getAllFilters();
    }

    private static class RegistrationRequest
    {
        private final DatagramChannel channel;

        private final IoHandler handler;

        private RegistrationRequest( DatagramChannel channel,
                                    IoHandler handler )
        {
            this.channel = channel;
            this.handler = handler;
        }
    }

    public ExceptionMonitor getExceptionMonitor()
    {
        return exceptionMonitor;
    }

    public void setExceptionMonitor( ExceptionMonitor monitor )
    {
        if( monitor == null )
        {
            monitor = new DefaultExceptionMonitor();
        }

        this.exceptionMonitor = monitor;
    }
}
