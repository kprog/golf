package com.thinkminimo.golf;

import org.json.*;

import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.HashMap;

import gnu.getopt.Getopt;
import gnu.getopt.LongOpt;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.mortbay.log.Log;
import org.mortbay.jetty.Handler;
import org.mortbay.jetty.HandlerContainer;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.handler.HandlerList;
import org.mortbay.jetty.handler.ContextHandlerCollection;
import org.mortbay.jetty.handler.DefaultHandler;
import org.mortbay.jetty.handler.ResourceHandler;
import org.mortbay.jetty.handler.RequestLogHandler;
import org.mortbay.jetty.servlet.Context;
import org.mortbay.jetty.servlet.ServletHolder;
import org.mortbay.jetty.servlet.DefaultServlet;
import org.mortbay.thread.QueuedThreadPool;

public class Main
{
  public static void main(String[] argv) {

    Integer   mPort         = 8080;
    String    mAppname      = null;
    String    mApproot      = null;
    String    mAwsPublic    = "";
    String    mAwsPrivate   = "";
    String    mDisplayName  = "";
    String    mDescription  = "";
    String    mDevmode      = "false";
    boolean   mDoWarfile    = false;

    HashMap<String, String> apps = new HashMap<String, String>();

    LongOpt[] longopts = {
      new LongOpt("port",         LongOpt.REQUIRED_ARGUMENT,  null,   'p'),
      new LongOpt("appname",      LongOpt.REQUIRED_ARGUMENT,  null,   'a'),
      new LongOpt("approot",      LongOpt.REQUIRED_ARGUMENT,  null,   'd'),
      new LongOpt("help",         LongOpt.NO_ARGUMENT,        null,   'h'),
      new LongOpt("version",      LongOpt.NO_ARGUMENT,        null,   'V'),
      new LongOpt("awsprivate",   LongOpt.REQUIRED_ARGUMENT,  null,    1 ),
      new LongOpt("awspublic",    LongOpt.REQUIRED_ARGUMENT,  null,    2 ),
      new LongOpt("displayname",  LongOpt.REQUIRED_ARGUMENT,  null,    3 ),
      new LongOpt("description",  LongOpt.REQUIRED_ARGUMENT,  null,    4 ),
      new LongOpt("war",          LongOpt.NO_ARGUMENT,        null,    5 ),
      new LongOpt("devmode",      LongOpt.REQUIRED_ARGUMENT,  null,    6 ),
    };

    // parse command line parameters
    Getopt g = new Getopt("golf", argv, "p:a:d:hV", longopts);
    int c;
    while ((c = g.getopt()) != -1) {
      switch (c) {
        case 'p':
          mPort = Integer.valueOf(g.getOptarg());
          break;
        case 'a':
          mAppname = g.getOptarg();
          break;
        case 'd':
          mApproot = g.getOptarg();
          break;
        case 'V':
          System.out.println("Golf version 0.1dev");
          System.exit(0);
          break;
        case 1:
          mAwsPrivate = g.getOptarg();
          break;
        case 2:
          mAwsPublic = g.getOptarg();
          break;
        case 3:
          mDisplayName = g.getOptarg();
          break;
        case 4:
          mDescription = g.getOptarg();
          break;
        case 5:
          mDoWarfile = true;
          break;
        case 6:
          mDevmode = g.getOptarg();
          break;
        case 'h':
          // fall through
        case '?':
          usage();
          break;
      }
    }

    if (mApproot == null || mAppname == null) {
      System.err.println("You must specify an approot and an appname!");
      System.exit(1);
    }

    if (mDoWarfile) {
      if (mDevmode == null)
        mDevmode = "false";

      try {
        GolfAnt gant = new GolfAnt();
        gant.setApproot(mApproot);
        gant.setAppname(mAppname);
        gant.setDisplayName(mDisplayName);
        gant.setDescription(mDescription);
        gant.setAwsPrivate(mAwsPrivate);
        gant.setAwsPublic(mAwsPublic);
        gant.setDevmode(mDevmode);
        gant.doit();
      } catch (Exception e) {
        e.printStackTrace();
        System.exit(1);
      }

      System.exit(0);
    }

    if (mDevmode == null)
      mDevmode = "true";

    apps.put(mAppname, mApproot);

    try {
      Server server = new Server(mPort);
      
      QueuedThreadPool threadPool = new QueuedThreadPool();
      threadPool.setMaxThreads(100);
      server.setThreadPool(threadPool);

      ContextHandlerCollection contexts = new ContextHandlerCollection();
      
      for (String app: apps.keySet()) {
        Log.info("Starting app `" + app + "'");

        String docRoot    = apps.get(app);

        String golfPath   = "/" + app;
        String golfRoot   = docRoot;

        Context cx1 = new Context(contexts, golfPath, Context.SESSIONS);
        cx1.setResourceBase(golfRoot);
        ServletHolder sh1 = new ServletHolder(new GolfServlet());
        sh1.setInitParameter("awsprivate", mAwsPrivate);
        sh1.setInitParameter("awspublic",  mAwsPublic);
        sh1.setInitParameter("devmode",    mDevmode);
        cx1.addServlet(sh1, "/*");
      }
      
      HandlerList handlers = new HandlerList();
      handlers.setHandlers(new Handler[] {contexts, new DefaultHandler(),
        new RequestLogHandler()});

      server.setHandler(handlers);
      server.setStopAtShutdown(true);
      server.setSendServerVersion(true);

      server.start();
      server.join();
    }
    catch (Exception x) {
      System.err.println( x.getMessage() );
    }
  }

  private static void usage() {
    System.out.println(
        "Usage: java -jar golf.jar [OPTIONS]\n"+
        "\n"+
        "OPTIONS:\n"+
        "     --port=PORT,    -p PORT     port for server to listen on\n"+
        "     --appname=NAME, -a NAME     context path of servlet\n"+
        "     --approot=DIR,  -d DIR      location of golf app directory\n"+
        "     --help,         -h          this help info right here\n"+
        "     --version,      -V          golf version (0.1dev)\n"+
        "     --awsprivate=KEY            amazon aws secret access key\n"+
        "     --awspublic=KEY             amazon aws access key ID\n"+
        "     --displayname=NAME          display name (optional)\n"+
        "     --description=DESC          app description (optional)\n"+
        "     --devmode=true/false        run in development mode (or not)\n"+
        "     --war                       create war file\n"+        
        "\n"
    );
    System.exit(1);
  }
}
