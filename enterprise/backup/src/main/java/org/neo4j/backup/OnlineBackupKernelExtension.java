/**
 * Copyright (c) 2002-2013 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.backup;

import java.net.URI;

import org.neo4j.cluster.BindingListener;
import org.neo4j.cluster.InstanceId;
import org.neo4j.cluster.client.ClusterClient;
import org.neo4j.cluster.com.BindingNotifier;
import org.neo4j.cluster.member.ClusterMemberAvailability;
import org.neo4j.cluster.member.ClusterMemberEvents;
import org.neo4j.cluster.member.ClusterMemberListener;
import org.neo4j.kernel.GraphDatabaseAPI;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.impl.core.KernelPanicEventGenerator;
import org.neo4j.kernel.impl.nioneo.store.StoreId;
import org.neo4j.kernel.impl.transaction.XaDataSourceManager;
import org.neo4j.kernel.lifecycle.Lifecycle;
import org.neo4j.kernel.logging.Logging;

public class OnlineBackupKernelExtension implements Lifecycle
{
    // This is the role used to announce that a cluster member can handle backups
    public static final String BACKUP = "backup";

    private Config config;
    private GraphDatabaseAPI graphDatabaseAPI;
    private XaDataSourceManager xaDataSourceManager;
    private KernelPanicEventGenerator kpeg;
    private Logging logging;
    private BackupServer server;
    private URI backupUri;
    private volatile URI me;

    public OnlineBackupKernelExtension( Config config, GraphDatabaseAPI graphDatabaseAPI, XaDataSourceManager
            xaDataSourceManager, KernelPanicEventGenerator kpeg, Logging logging )
    {
        this.config = config;
        this.graphDatabaseAPI = graphDatabaseAPI;
        this.xaDataSourceManager = xaDataSourceManager;
        this.kpeg = kpeg;
        this.logging = logging;
    }

    @Override
    public void init() throws Throwable
    {
    }

    @Override
    public void start() throws Throwable
    {
        if ( config.<Boolean>get( OnlineBackupSettings.online_backup_enabled ) )
        {
            TheBackupInterface backup = new BackupImpl( logging.getMessagesLog( BackupImpl.class ), new BackupImpl.SPI()
            {
                @Override
                public String getStoreDir()
                {
                    return graphDatabaseAPI.getStoreDir();
                }

                @Override
                public StoreId getStoreId()
                {
                    return graphDatabaseAPI.getStoreId();
                }
            }, xaDataSourceManager, kpeg );
            try
            {
                server = new BackupServer( backup,
                        config.get( OnlineBackupSettings.online_backup_server ), logging );
                server.init();
                server.start();

                try
                {
                    graphDatabaseAPI.getDependencyResolver().resolveDependency( ClusterMemberEvents.class).addClusterMemberListener(
                            new StartBindingListener() );

                    graphDatabaseAPI.getDependencyResolver().resolveDependency( BindingNotifier.class ).addBindingListener( new BindingListener()
                            {
                                @Override
                                public void listeningAt( URI myUri )
                                {
                                    me = myUri;
                                }
                            } );
                }
                catch ( NoClassDefFoundError e )
                {
                    // Not running HA
                }
                catch ( IllegalArgumentException e )
                {
                    // HA available, but not used
                }
            }
            catch ( Throwable t )
            {
                throw new RuntimeException( t );
            }
        }
    }

    @Override
    public void stop() throws Throwable
    {
        if ( server != null )
        {
            server.stop();
            server.shutdown();
            server = null;

            try
            {
                ClusterMemberAvailability client = graphDatabaseAPI.getDependencyResolver().resolveDependency( ClusterMemberAvailability.class );
                client.memberIsUnavailable( BACKUP );
            }
            catch ( NoClassDefFoundError e )
            {
                // Not running HA
            }
            catch ( IllegalArgumentException e )
            {
                // HA available, but not used
            }
        }
    }

    @Override
    public void shutdown() throws Throwable
    {
    }

    private class StartBindingListener extends ClusterMemberListener.Adapter
    {

        @Override
        public void memberIsAvailable( String role, InstanceId available, URI availableAtUri )
        {
            if ( graphDatabaseAPI.getDependencyResolver().resolveDependency( ClusterClient.class ).
                    getServerId().equals( available ) && "master".equals( role ) )
            {
                // It was me and i am master - yey!
                {
                    try
                    {
                        ClusterMemberAvailability ha = graphDatabaseAPI.getDependencyResolver().resolveDependency( ClusterMemberAvailability.class );
                        backupUri = URI.create( "backup://" + (server.getSocketAddress().getHostString().contains("0.0.0.0")?me.getHost():server.getSocketAddress().getHostString()) + ":" + server.getSocketAddress().getPort() );
                        ha.memberIsAvailable( BACKUP, backupUri );
                    }
                    catch ( Throwable t )
                    {
                        throw new RuntimeException( t );
                    }
                }
            }
        }

        @Override
        public void memberIsUnavailable( String role, InstanceId unavailableId )
        {
            if ( graphDatabaseAPI.getDependencyResolver().resolveDependency( ClusterClient.class ).
                    getServerId().equals( unavailableId ) && "master".equals( role ) )
            {
                // It was me and i am master - yey!
                {
                    try
                    {
                        ClusterMemberAvailability ha = graphDatabaseAPI.getDependencyResolver().resolveDependency( ClusterMemberAvailability.class );
                        backupUri = URI.create( "backup://" + (server.getSocketAddress().getHostString().contains("0.0.0.0")?me.getHost():server.getSocketAddress().getHostString()) + ":" + server.getSocketAddress().getPort() );
                        ha.memberIsUnavailable( BACKUP );
                    }
                    catch ( Throwable t )
                    {
                        throw new RuntimeException( t );
                    }
                }
            }
        }
    }
}
