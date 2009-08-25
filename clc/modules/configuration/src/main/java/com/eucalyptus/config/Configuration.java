package com.eucalyptus.config;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.InvocationTargetException;
import java.security.KeyPair;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import com.eucalyptus.auth.ClusterCredentials;
import com.eucalyptus.auth.Credentials;
import com.eucalyptus.auth.Hashes;
import com.eucalyptus.auth.SystemCredentialProvider;
import com.eucalyptus.auth.X509Cert;
import com.eucalyptus.auth.util.KeyTool;
import com.eucalyptus.bootstrap.Component;
import com.eucalyptus.util.EntityWrapper;
import com.eucalyptus.util.EucalyptusCloudException;
import com.eucalyptus.util.EucalyptusProperties;
import com.eucalyptus.util.SubDirectory;

import edu.ucsb.eucalyptus.msgs.ComponentInfoType;
import edu.ucsb.eucalyptus.msgs.ConfigurationMessage;
import edu.ucsb.eucalyptus.msgs.DeregisterClusterType;
import edu.ucsb.eucalyptus.msgs.DeregisterComponentResponseType;
import edu.ucsb.eucalyptus.msgs.DeregisterComponentType;
import edu.ucsb.eucalyptus.msgs.DescribeComponentsResponseType;
import edu.ucsb.eucalyptus.msgs.DescribeComponentsType;
import edu.ucsb.eucalyptus.msgs.RegisterClusterType;
import edu.ucsb.eucalyptus.msgs.RegisterComponentResponseType;
import edu.ucsb.eucalyptus.msgs.RegisterComponentType;

public class Configuration {
  private static Logger LOG                 = Logger.getLogger( Configuration.class );
  private static String DB_NAME             = "eucalyptus_config";
  private static String CLUSTER_KEY_FSTRING = "cc-%s";
  private static String NODE_KEY_FSTRING    = "nc-%s";

  public static <T> EntityWrapper<T> getEntityWrapper( ) {
    return new EntityWrapper<T>( Configuration.DB_NAME );
  }

  private static Map<String, Class> typeMap = new HashMap<String, Class>( ) {
                                              {
                                                put( "Cluste", ClusterConfiguration.class );
                                                put( "Storag", StorageControllerConfiguration.class );
                                                put( "Walrus", WalrusConfiguration.class );
                                              }
                                            };

  private static ComponentConfiguration getConfigurationInstance( ConfigurationMessage request, Object... args ) throws InstantiationException, IllegalAccessException, InvocationTargetException, NoSuchMethodException {
    Class[] classList = new Class[args.length];
    for ( int i = 0; i < args.length; i++ ) {
      classList[i] = args[i].getClass( );
    }
    String cname = request.getComponentName( );
    Class cclass = Configuration.typeMap.get( cname );
    ComponentConfiguration configInstance = ( ComponentConfiguration ) cclass.getConstructor( classList ).newInstance( args );
    return configInstance;
  }

  public RegisterComponentResponseType registerComponent( RegisterComponentType request ) throws EucalyptusCloudException {
    RegisterComponentResponseType reply = ( RegisterComponentResponseType ) request.getReply( );
    reply.set_return( true );
    if ( this.checkComponentExists( request ) ) { return reply; }

    EntityWrapper<ComponentConfiguration> db = Configuration.getEntityWrapper( );
    ComponentConfiguration newComponent;
    try {
      newComponent = Configuration.getConfigurationInstance( request, request.getName( ), request.getHost( ), request.getPort( ) );
      db.add( newComponent );
      db.commit( );
    } catch ( Exception e ) {
      db.rollback( );
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    if ( request instanceof RegisterClusterType ) {
      Configuration.setupClusterCredentials( newComponent );
    }
    return reply;
  }

  private static void setupClusterCredentials( ComponentConfiguration newComponent ) throws EucalyptusCloudException {
    /** generate the Component keys **/
    String ccAlias = String.format( CLUSTER_KEY_FSTRING, newComponent.getName( ) );
    String ncAlias = String.format( NODE_KEY_FSTRING, newComponent.getName( ) );
    String directory = SubDirectory.KEYS.toString( ) + File.separator + newComponent.getName( );
    File keyDir = new File( directory );
    LOG.info( "creating keys in " + directory );
    if( !keyDir.mkdir( ) ) {
      throw new EucalyptusCloudException( "Failed to create cluster key directory: " + keyDir.getAbsolutePath( ) );
    }
    EntityWrapper<ClusterCredentials> credDb = Credentials.getEntityWrapper( );
    try {
      KeyTool keyTool = new KeyTool( );
      KeyPair clusterKp = keyTool.getKeyPair( );
      X509Certificate clusterX509 = keyTool.getCertificate( clusterKp, EucalyptusProperties.getDName( "cc-" + newComponent.getName( ) ) );
      keyTool.writePem( directory + File.separator + "cluster-pk.pem", clusterKp.getPrivate( ) );
      keyTool.writePem( directory + File.separator + "cluster-cert.pem", clusterX509 );

      KeyPair nodeKp = keyTool.getKeyPair( );
      X509Certificate nodeX509 = keyTool.getCertificate( nodeKp, EucalyptusProperties.getDName( "nc-" + newComponent.getName( ) ) );
      keyTool.writePem( directory + File.separator + "node-pk.pem", nodeKp.getPrivate( ) );
      keyTool.writePem( directory + File.separator + "node-cert.pem", nodeX509 );

      X509Certificate systemX509 = SystemCredentialProvider.getCredentialProvider( Component.eucalyptus ).getCertificate( );
      keyTool.writePem( SubDirectory.KEYS.toString( ) + File.separator + "cloud-cert.pem", systemX509 );
      Signature signer = Signature.getInstance( "SHA256withRSA" );
      signer.initSign( SystemCredentialProvider.getCredentialProvider( Component.eucalyptus ).getPrivateKey( ) );
      signer.update( "eucalyptus".getBytes( ) );
      byte[] sig = signer.sign( );
      FileWriter out = new FileWriter( directory + File.separator + "vtunpass" );
      String hexSig = Hashes.bytesToHex( sig );
      out.write( hexSig );
      out.flush( );
      out.close( );

      ClusterCredentials ComponentCredentials = new ClusterCredentials( newComponent.getName( ) );
      ComponentCredentials.setClusterCertificate( X509Cert.fromCertificate( ccAlias, clusterX509 ) );
      ComponentCredentials.setNodeCertificate( X509Cert.fromCertificate( ncAlias, nodeX509 ) );
      credDb.add( ComponentCredentials );
      credDb.commit( );
    } catch ( Exception eee ) {
      credDb.rollback( );
      throw new EucalyptusCloudException( eee );
    }
  }

  private boolean checkComponentExists( RegisterComponentType request ) throws EucalyptusCloudException {
    EntityWrapper<ComponentConfiguration> db = Configuration.getEntityWrapper( );

    ComponentConfiguration existingName = null;
    ComponentConfiguration existingHost = null;

    try {
      ComponentConfiguration searchConfig = Configuration.getConfigurationInstance( request, request.getName( ), request.getHost( ), request.getPort( ) );
      existingName = db.getUnique( searchConfig );
      return true;
    } catch ( Exception e ) {
      try {
        ComponentConfiguration searchConfig = Configuration.getConfigurationInstance( request );
        searchConfig.setName( request.getName( ) );
        existingName = db.getUnique( searchConfig );
      } catch ( Exception e1 ) {
        try {
          ComponentConfiguration searchConfig = Configuration.getConfigurationInstance( request );
          searchConfig.setHostName( request.getHost( ) );
          existingHost = db.getUnique( searchConfig );
        } catch ( Exception e2 ) {
        }
      }
    } finally {
      db.rollback( );
    }
    if ( existingName != null ) {
      throw new EucalyptusCloudException( "Component with name=" + request.getName( ) + " already exists at host=" + existingName.getHostName( ) );
    } else if ( existingHost != null ) { throw new EucalyptusCloudException( "Component at host=" + existingHost.getHostName( ) + " already exists with name=" + request.getName( ) ); }
    return false;
  }

  public DeregisterComponentResponseType deregisterComponent( DeregisterComponentType request ) throws EucalyptusCloudException {
    EntityWrapper<ComponentConfiguration> db = Configuration.getEntityWrapper( );
    try {
      ComponentConfiguration searchConfig = Configuration.getConfigurationInstance( request );
      searchConfig.setName( request.getName( ) );
      ComponentConfiguration componentConfig = db.getUnique( searchConfig );
      db.delete( componentConfig );
      db.commit( );
    } catch ( Exception e ) {
      db.rollback( );
      throw e instanceof EucalyptusCloudException ? ( EucalyptusCloudException ) e : new EucalyptusCloudException( e );
    }
    if ( request instanceof DeregisterClusterType ) {
      try {
        Configuration.removeClusterCredentials( request.getName( ) );
      } catch ( Exception e ) {
        LOG.error( "BUG: removed cluster but failed to remove the credentials." );
      }
    }
    DeregisterComponentResponseType reply = ( DeregisterComponentResponseType ) request.getReply( );
    reply.set_return( true );
    return reply;
  }

  private static void removeClusterCredentials( String clusterName ) {
    EntityWrapper<ClusterCredentials> credDb = Credentials.getEntityWrapper( );
    try {
      ClusterCredentials clusterCredentials = new ClusterCredentials( clusterName );
      String directory = SubDirectory.KEYS.toString( ) + File.separator + clusterName;
      credDb.delete( clusterCredentials );
      File keyDir = new File( directory );
      for ( File f : keyDir.listFiles( ) ) {
        if ( !f.delete( ) ) {
          LOG.warn( "Failed to delete key file: " + f.getAbsolutePath( ) );
        }
      }
      if ( !keyDir.delete( ) ) {
        LOG.warn( "Failed to delete key directory: " + keyDir.getAbsolutePath( ) );
      }
    } catch ( Exception e ) {
      credDb.rollback( );
    }
    credDb.commit( );
  }

  public DescribeComponentsResponseType listComponents( DescribeComponentsType request ) throws EucalyptusCloudException {
    DescribeComponentsResponseType reply = ( DescribeComponentsResponseType ) request.getReply( );
    ComponentConfiguration searchConfig;
    try {
      searchConfig = Configuration.getConfigurationInstance( request );
    } catch ( Exception e1 ) {
      LOG.error( "Failed to find configuration type for request of type: " + request.getClass( ).getSimpleName( ) );
      throw new EucalyptusCloudException( e1 );
    }
    List<ComponentInfoType> listConfigs = reply.getRegistered( );
    EntityWrapper<ComponentConfiguration> db = Configuration.getEntityWrapper( );
    try {
      List<ComponentConfiguration> componentList = db.query( searchConfig );
      for ( ComponentConfiguration c : componentList ) {
        listConfigs.add( new ComponentInfoType( c.getName( ), c.getHostName( ) ) );
      }
    } catch ( Exception e ) {
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    } finally {
      db.commit( );
    }
    return reply;
  }
  
  public static List<ClusterConfiguration> getClusterConfigurations() throws EucalyptusCloudException {
    EntityWrapper<ClusterConfiguration> db = Configuration.getEntityWrapper( );
    try {
      List<ClusterConfiguration> componentList = db.query( new ClusterConfiguration( ) );
      return componentList;
    } catch ( Exception e ) {
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    } finally {
      db.commit( );
    }
  }
  public static List<StorageControllerConfiguration> getStorageControllerConfigurations() throws EucalyptusCloudException {
    EntityWrapper<StorageControllerConfiguration> db = Configuration.getEntityWrapper( );
    try {
      List<StorageControllerConfiguration> componentList = db.query( new StorageControllerConfiguration( ) );
      return componentList;
    } catch ( Exception e ) {
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    } finally {
      db.commit( );
    }
  }
  public static List<WalrusConfiguration> getWalrusConfigurations() throws EucalyptusCloudException {
    EntityWrapper<WalrusConfiguration> db = Configuration.getEntityWrapper( );
    try {
      List<WalrusConfiguration> componentList = db.query( new WalrusConfiguration( ) );
      return componentList;
    } catch ( Exception e ) {
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    } finally {
      db.commit( );
    }
  }
}
