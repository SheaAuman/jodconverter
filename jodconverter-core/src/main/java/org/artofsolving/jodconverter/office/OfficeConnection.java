/*
 * Copyright 2004 - 2012 Mirko Nasato and contributors
 *           2016 - 2017 Simon Braconnier and contributors
 *
 * This file is part of JODConverter - Java OpenDocument Converter.
 *
 * JODConverter is an Open Source software: you can redistribute it and/or
 * modify it under the terms of either (at your option) of the following
 * licenses:
 *
 * 1. The GNU Lesser General Public License v3 (or later)
 *    http://www.gnu.org/licenses/lgpl-3.0.txt
 * 2. The Apache License, Version 2.0
 *    http://www.apache.org/licenses/LICENSE-2.0.txt
 */

package org.artofsolving.jodconverter.office;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.star.beans.XPropertySet;
import com.sun.star.bridge.XBridge;
import com.sun.star.bridge.XBridgeFactory;
import com.sun.star.comp.helper.Bootstrap;
import com.sun.star.connection.XConnection;
import com.sun.star.connection.XConnector;
import com.sun.star.frame.XComponentLoader;
import com.sun.star.frame.XDesktop;
import com.sun.star.lang.EventObject;
import com.sun.star.lang.XComponent;
import com.sun.star.lang.XEventListener;
import com.sun.star.lang.XMultiComponentFactory;
import com.sun.star.lib.uno.helper.UnoUrl;
import com.sun.star.uno.UnoRuntime;
import com.sun.star.uno.XComponentContext;

class OfficeConnection implements OfficeContext, XEventListener {

  private static AtomicInteger bridgeIndex = new AtomicInteger();
  private static final Logger logger = LoggerFactory.getLogger(OfficeConnection.class);

  private final UnoUrl unoUrl;
  private Object desktopService;
  private XComponent bridgeComponent;
  private XComponentLoader officeComponentLoader;
  private final List<OfficeConnectionEventListener> connectionEventListeners;
  private final AtomicBoolean connected = new AtomicBoolean();

  /**
   * Constructs a new connection for the specified UNO URL.
   *
   * @param unoUrl the URL for which the connection is created.
   */
  public OfficeConnection(final UnoUrl unoUrl) {

    this.unoUrl = unoUrl;
    this.connectionEventListeners = new ArrayList<OfficeConnectionEventListener>();
  }

  /**
   * Adds a listener to the connection event listener list of this connection.
   *
   * @param connectionEventListener the listener to add. It will be notified when a connection is
   *     established with an office process and when a connection is lost.
   */
  public void addConnectionEventListener(
      final OfficeConnectionEventListener connectionEventListener) {

    connectionEventListeners.add(connectionEventListener);
  }

  /** Establishes the connection to an office instance. */
  public synchronized void connect() throws OfficeConnectionException {

    final String connectPart = unoUrl.getConnectionAndParametersAsString();
    logger.debug("Connecting with connectString '{}'", connectPart);
    try {
      // Create a default local component context.
      final XComponentContext localContext = Bootstrap.createInitialComponentContext(null);
      // Initial service manager.
      final XMultiComponentFactory localServiceManager = localContext.getServiceManager();

      // Instantiate a connector service.
      Object x =
          localServiceManager.createInstanceWithContext(
              "com.sun.star.connection.Connector", localContext);
      final XConnector connector = UnoRuntime.queryInterface(XConnector.class, x);

      // Connect using the connection string part of the uno-url only.
      final XConnection connection = connector.connect(connectPart);

      // Instantiate a bridge factory service.
      x =
          localServiceManager.createInstanceWithContext(
              "com.sun.star.bridge.BridgeFactory", localContext);
      final XBridgeFactory bridgeFactory = UnoRuntime.queryInterface(XBridgeFactory.class, x);

      // Create a remote bridge with no instance provider using the urp protocol.
      final String bridgeName = "jodconverter_" + bridgeIndex.getAndIncrement();
      final XBridge bridge =
          bridgeFactory.createBridge(
              bridgeName, unoUrl.getProtocolAndParametersAsString(), connection, null);

      // Query for the XComponent interface and add this as event listener.
      bridgeComponent = UnoRuntime.queryInterface(XComponent.class, bridge);
      bridgeComponent.addEventListener(this);

      // Get the remote instance
      final String rootOid = unoUrl.getRootOid();
      x = bridge.getInstance(rootOid);
      // Did the remote server export this object ?
      if (x == null) {
        throw new OfficeConnectionException(
            "Server didn't provide an instance for '" + rootOid + "'", connectPart);
      }

      // Query the initial object for its main factory interface.
      final XMultiComponentFactory officeMultiComponentFactory =
          UnoRuntime.queryInterface(XMultiComponentFactory.class, x);

      // Retrieve the component context (it's not yet exported from the office)
      // Query for the XPropertySet interface.
      final XPropertySet properties =
          UnoRuntime.queryInterface(XPropertySet.class, officeMultiComponentFactory);

      // Get the default context from the office server.
      x = properties.getPropertyValue("DefaultContext");

      // Query for the interface XComponentContext.
      final XComponentContext officeComponentContext =
          UnoRuntime.queryInterface(XComponentContext.class, x);

      // Now create the desktop service
      // NOTE: use the office component context here !
      desktopService =
          officeMultiComponentFactory.createInstanceWithContext(
              "com.sun.star.frame.Desktop", officeComponentContext);
      officeComponentLoader = UnoRuntime.queryInterface(XComponentLoader.class, desktopService);
      if (officeComponentLoader == null) {
        throw new OfficeConnectionException(
            "Couldn't instantiate com.sun.star.frame.Desktop", connectPart);
      }

      // We are now connected
      connected.set(true);
      logger.info("Connected: '{}'", connectPart);

      // Inform all the listener that we are connected
      final OfficeConnectionEvent connectionEvent = new OfficeConnectionEvent(this);
      for (final OfficeConnectionEventListener listener : connectionEventListeners) {
        listener.connected(connectionEvent);
      }

    } catch (OfficeConnectionException connectionEx) {
      throw connectionEx;

    } catch (Exception ex) {
      throw new OfficeConnectionException(
          String.format("Connection failed: '%s'; %s", connectPart, ex.getMessage()),
          connectPart,
          ex);
    }
  }

  /** Closes the connection. */
  public synchronized void disconnect() {

    logger.debug("Disconnecting from '{}'", unoUrl.getConnectionAndParametersAsString());

    // Dispose of the bridge
    bridgeComponent.dispose();
  }

  @Override
  public void disposing(final EventObject eventObject) {

    if (connected.get()) {
      // Remote bridge has gone down, because the office crashed or was terminated.
      connected.set(false);
      officeComponentLoader = null;
      desktopService = null;
      bridgeComponent = null;

      logger.info("Disconnected: '{}'", unoUrl.getConnectionAndParametersAsString());

      // Inform listeners. Must be done at the end since a listener may recreated the bridge
      final OfficeConnectionEvent connectionEvent = new OfficeConnectionEvent(this);
      for (final OfficeConnectionEventListener listener : connectionEventListeners) {
        listener.disconnected(connectionEvent);
      }
    }
    // else we tried to connect to a server that doesn't speak URP
  }

  @Override
  public XComponentLoader getComponentLoader() {

    return officeComponentLoader;
  }

  @Override
  public XDesktop getDesktop() {

    // Needed only when stopping a process for now, so no need to keep an instance of it.
    return UnoRuntime.queryInterface(XDesktop.class, desktopService);
  }

  /**
   * Gets whether we are connected to an office instance.
   *
   * @return {@code true} if we are connected to an office instance; {@code false} otherwise.
   */
  public boolean isConnected() {

    return connected.get();
  }
}
