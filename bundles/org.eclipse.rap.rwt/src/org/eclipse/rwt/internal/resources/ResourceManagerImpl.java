/*******************************************************************************
 * Copyright (c) 2002, 2011 Innoopract Informationssysteme GmbH and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Innoopract Informationssysteme GmbH - initial API and implementation
 *    EclipseSource - ongoing development
 *    Frank Appel - replaced singletons and static fields (Bug 337787)
 ******************************************************************************/
package org.eclipse.rwt.internal.resources;

import java.io.*;
import java.net.*;
import java.text.MessageFormat;
import java.util.*;

import org.eclipse.rwt.internal.engine.RWTConfiguration;
import org.eclipse.rwt.internal.util.ParamCheck;
import org.eclipse.rwt.resources.IResourceManager;


/**
 * The resource manager is responsible for registering resources like images,
 * CSS files etc. which are available on the application's classpath. The
 * registered files will be read out from their libraries and delivered if
 * requested. Usually resources are stored in libraries in the WEB-INF/lib
 * directory of a web-application
 * <p>
 * Implementation as singleton.
 * </p>
 * <p>
 * This class is not intended to be used by clients.
 * </p>
 */
public class ResourceManagerImpl implements IResourceManager {

  public static final String RESOURCES = "rwt-resources";

  private final Map<String,String> repository;
  private final Map<String,Resource> cache;
  private final RWTConfiguration configuration;
  private final ThreadLocal<ClassLoader> contextLoader;

  private static final class Resource {

    private final String charset;
    private final Integer version;

    public Resource( String charset, Integer version ) {
      this.charset = charset;
      this.version = version;
    }

    public String getCharset() {
      return charset;
    }

    public Integer getVersion() {
      return version;
    }
  }

  public ResourceManagerImpl( RWTConfiguration configuration ) {
    this.configuration = configuration;
    repository = new Hashtable<String,String>();
    cache = new Hashtable<String,Resource>();
    contextLoader = new ThreadLocal<ClassLoader>();
  }

  /**
   * Returns the version number for the previously
   * {@link #register(String, String, RegisterOptions) registered} resource.
   *
   * @param name the name of the resource for which the version number should be
   *          obtained. Must not be <code>null</code>.
   * @return the version number or <code>null</code> if either no such resource
   *         was registered or the resource does not have a version number.
   * @throws NullPointerException when <<code>name</code> is <code>null</code>.
   */
  public Integer findVersion( String name ) {
    ParamCheck.notNull( name, "name" );
    Integer result = null;
    Resource resource = cache.get( createKey( name ) );
    if( resource != null ) {
      result = resource.getVersion();
    }
    return result;
  }

  /////////////////////////////
  // interface IResourceManager

  public void register( String name ) {
    ParamCheck.notNull( name, "name" );
    doRegister( name, null, RegisterOptions.NONE );
  }

  public void register( String name, String charset ) {
    ParamCheck.notNull( name, "name" );
    ParamCheck.notNull( charset, "charset" );
    doRegister( name, charset, RegisterOptions.NONE );
  }

  public void register( String name, String charset, RegisterOptions options ) {
    ParamCheck.notNull( name, "name" );
    ParamCheck.notNull( charset, "charset" );
    ParamCheck.notNull( options, "options" );
    doRegister( name, charset, options );
  }

  public void register( String name, InputStream is ) {
    ParamCheck.notNull( name, "name" );
    ParamCheck.notNull( is, "is" );
    String key = createKey( name );
    try {
      byte[] content = ResourceUtil.readBinary( is );
      doRegister( name, null, RegisterOptions.NONE, key, content );
    } catch ( IOException e ) {
      String text = "Failed to register resource ''{0}''.";
      String msg = MessageFormat.format( text, new Object[] { name } );
      throw new ResourceRegistrationException( msg, e ) ;
    }
    repository.put( key, name );
  }

  public void register( String name, InputStream is, String charset, RegisterOptions options ) {
    ParamCheck.notNull( name, "name" );
    ParamCheck.notNull( is, "is" );
    ParamCheck.notNull( charset, "charset" );
    ParamCheck.notNull( options, "options" );
    boolean compress = shouldCompress( options );
    String key = createKey( name );
    try {
      byte[] content = ResourceUtil.read( is, charset, compress );
      doRegister( name, charset, options, key, content );
    } catch ( IOException ioe ) {
      String msg = "Failed to register resource: " + name;
      throw new ResourceRegistrationException( msg, ioe ) ;
    }
    repository.put( key, name );
  }

  public boolean unregister( String name ) {
    ParamCheck.notNull( name, "name" );
    boolean result = false;
    String key = createKey( name );
    String fileName = repository.remove( key );
    if( fileName != null ) {
      result = true;
      Integer version = findVersion( name );
      File file = getDiskLocation( name, version );
      file.delete();
      cache.remove( key );
    }
    return result;
  }

  public String getCharset( String name ) {
    ParamCheck.notNull( name, "name" );
    Resource resource = cache.get( createKey( name ) );
    return resource.getCharset();
  }

  public boolean isRegistered( String name ) {
    ParamCheck.notNull( name, "name" );
    String key = createKey( name );
    String fileName = repository.get( key );
    return fileName != null;
  }

  public String getLocation( String name ) {
    ParamCheck.notNull( name, "name" );
    String key = createKey( name );
    String fileName = repository.get( key );
    return createRequestURL( fileName, findVersion( name ) );
  }

  public URL getResource( String name ) {
    return getLoader().getResource( name );
  }

  public InputStream getResourceAsStream( String name ) {
    URL resource = getLoader().getResource( name );
    InputStream result = null;
    if( resource != null ) {
      try {
        URLConnection connection = resource.openConnection();
        connection.setUseCaches( false );
        result = connection.getInputStream();
      } catch( IOException ignore ) {
        // ignore
      }
    }
    return result;
  }

  public Enumeration getResources( String name ) throws IOException {
    return getLoader().getResources( name );
  }

  public void setContextLoader( ClassLoader classLoader ) {
    contextLoader.set( classLoader );
  }

  public ClassLoader getContextLoader() {
    return contextLoader.get();
  }

  public InputStream getRegisteredContent( String name ) {
    InputStream result = null;
    String key = createKey( name );
    String fileName = repository.get( key );
    if( fileName != null ) {
      // TODO [rst] Works only for non-versioned content for now
      File file = getDiskLocation( name, null );
      try {
        result = new FileInputStream( file );
      } catch( FileNotFoundException e ) {
        // should not happen
        throw new RuntimeException( e );
      }
    }
    return result;
  }

  //////////////////
  // helping methods

  private ClassLoader getLoader() {
    ClassLoader result;
    if( getContextLoader() != null ) {
      result = getContextLoader();
    } else {
      result = getClass().getClassLoader();
    }
    return result;
  }

  private static String createKey( String name ) {
    return String.valueOf( name.hashCode() );
  }

  private String createRequestURL( String fileName, Integer version ) {
    String newFileName = fileName.replace( '\\', '/' );
    StringBuilder url = new StringBuilder();
    url.append( RESOURCES );
    url.append( "/" );
    String escapedFilename = escapeFilename( newFileName );
    url.append( versionedResourceName( escapedFilename, version ) );
    return url.toString();
  }

  private void doRegister( String name, String charset, RegisterOptions options ) {
    String key = createKey( name );
    // TODO [rh] should throw exception if contains key but has different
    //      charset or options
    if( !repository.containsKey( key ) ) {
      boolean compress = shouldCompress( options );
      try {
        byte[] content = ResourceUtil.read( name, charset, compress, this );
        doRegister( name, charset, options, key, content );
      } catch ( IOException e ) {
        String text = "Failed to register resource ''{0}''.";
        String msg = MessageFormat.format( text, new Object[] { name } );
        throw new ResourceRegistrationException( msg, e ) ;
      }
      repository.put( key, name );
    }
  }

  private void doRegister( String name,
                           String charset,
                           RegisterOptions options,
                           String key,
                           byte[] content )
    throws IOException
  {
    Integer version = computeVersion( content, options );
    File location = getDiskLocation( name, version );
    createFile( location );
    ResourceUtil.write( location, content );
    cache.put( key, new Resource( charset, version ) );
  }

  private static void createFile( File fileToWrite ) throws IOException {
    File dir = new File( fileToWrite.getParent() );
    if( !dir.mkdirs() ) {
      if( !dir.exists() ) {
        throw new IOException( "Could not create directory structure: " + dir );
      }
    }
    if( !fileToWrite.exists() ) {
      fileToWrite.createNewFile();
    }
  }

  private static Integer computeVersion( byte[] content, RegisterOptions options ) {
    Integer result = null;
    if( content != null && shouldVersion( options ) ) {
      int version = 0;
      for( int i = 0; i < content.length; i++ ) {
        version = version * 31 + content[ i ];
      }
      result = new Integer( version );
    }
    return result;
  }

  static String versionedResourceName( String name, Integer version ) {
    String result = name;
    if( version != null ) {
      String versionString = Integer.toHexString( version.intValue() );
      int dotPos = name.lastIndexOf( '.' );
      // ensure that the dot was found in name part (not path)
      if( name.replace( '\\', '/' ).lastIndexOf( "/" ) > dotPos  ) {
        dotPos = -1;
      }
      if( dotPos == -1 ) {
        // append version number if not suffix
        result = name + '-' + versionString;
      } else {
        // insert version number between name and suffix
        result = name.substring( 0, dotPos ) + '-' + versionString + name.substring( dotPos );
      }
    }
    return result;
  }

  private static boolean shouldVersion( RegisterOptions options ) {
    return    (    options == RegisterOptions.VERSION
                || options == RegisterOptions.VERSION_AND_COMPRESS )
           && SystemProps.useVersionedJavaScript();
  }

  private static boolean shouldCompress( RegisterOptions options ) {
    return    (    options == RegisterOptions.COMPRESS
                || options == RegisterOptions.VERSION_AND_COMPRESS )
           && SystemProps.useCompressedJavaScript()
           && !SystemProps.isDevelopmentMode();
  }

  private File getDiskLocation( String name, Integer version ) {
    StringBuilder filename = new StringBuilder();
    filename.append( configuration.getContextDirectory().toString() );
    filename.append( File.separator );
    filename.append( RESOURCES );
    filename.append( File.separator );
    filename.append( versionedResourceName( escapeFilename( name ), version ) );
    return new File( filename.toString() );
  }

  private static String escapeFilename( String name ) {
    String result = name;
    result = name.replaceAll( "\\$", "\\$\\$" );
    result = result.replaceAll( ":", "\\$1" );
    result = result.replaceAll( "\\?", "\\$2" );
    return result;
  }

}
