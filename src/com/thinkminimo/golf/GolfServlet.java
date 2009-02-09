package com.thinkminimo.golf;

import org.json.JSONStringer;
import org.json.JSONException;

import org.jets3t.service.S3Service;
import org.jets3t.service.model.S3Bucket;
import org.jets3t.service.impl.rest.httpclient.RestS3Service;
import org.jets3t.service.security.AWSCredentials;

import java.io.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.*;
import org.mozilla.javascript.*;

import java.net.*;

import javax.servlet.*;
import javax.servlet.http.*;

import org.mortbay.log.Log;
import org.mortbay.jetty.servlet.DefaultServlet;

import com.gargoylesoftware.htmlunit.*;
import com.gargoylesoftware.htmlunit.html.*;
import com.gargoylesoftware.htmlunit.javascript.*;

/**
 * Golf servlet class!
 */
public class GolfServlet extends HttpServlet {
  
  public static final int     LOG_DEBUG           = 1;
  public static final int     LOG_INFO            = 2;
  public static final int     LOG_WARN            = 3;

  public static final int     JSVM_TIMEOUT        = 10000;

  public static final String  FILE_USER_AGENTS    = "user-agents.xml";
  public static final String  FILE_NEW_HTML       = "new.html";
  public static final String  FILE_JSDETECT_HTML  = "jsdetect.html";

  private class StoredJSVM {
    public WebClient                      client;
    public HtmlPage                       lastPage;

    StoredJSVM(WebClient client) {
      this.client   = client;
      this.lastPage = null;
    }
  }

  public class RedirectException extends Exception {
    public RedirectException(String msg) {
      super(msg);
    }
  }

  private class GolfSession {
    private HttpSession mSess;

    public GolfSession(HttpServletRequest req) { 
      mSess = req.getSession(); 
    }

    private String get(String name) { 
      return (String) mSess.getAttribute(name);
    }

    private void set(String name, String value) { 
      mSess.setAttribute(name, value);
    }

    public Integer getSeq() { 
      try { 
        return Integer.parseInt(get("golf"));
      } catch (NumberFormatException e) { }
      return null;
        
    }
    public void setSeq(Integer value) {
      set("golf", String.valueOf(value));
    }

    public Boolean getJs() {
      return get("js") == null ? null : Boolean.parseBoolean(get("js"));
    }
    public void setJs(boolean value) {
      set("js", String.valueOf(value));
    }

    public String getIpAddr() {
      return get("ipaddr");
    }
    public void setIpAddr(String value) {
      set("ipaddr", value);
    }

    public String getLastURL() {
      return get("lasturl");
    }
    public void setLastURL(String value) {
      set("lasturl", value);
    }

    public String getLastEvent() {
      return get("lastevent");
    }
    public void setLastEvent(String value) {
      set("lastevent", value);
    }

    public String getLastTarget() {
      return get("lasttarget");
    }
    public void setLastTarget(String value) {
      set("lasttarget", value);
    }
  }

  private class GolfParams {
    private String    mEvent      = null;
    private String    mTarget     = null;
    private Boolean   mForce      = false;
    private Integer   mSeq        = -1;
    private Boolean   mJs         = false;
    private String    mPath       = null;
    private String    mComponent  = null;

    public GolfParams(HttpServletRequest req) {
      mEvent      = req.getParameter("event");
      mTarget     = req.getParameter("target");
      mForce      = req.getParameter("force") == null 
                      ? null
                      : Boolean.parseBoolean(req.getParameter("force"));
      mSeq        = req.getParameter("golf") == null 
                      ? null
                      : Integer.valueOf(req.getParameter("golf"));
      mJs         = req.getParameter("js") == null 
                      ? null
                      : Boolean.parseBoolean(req.getParameter("js"));
      mPath       = req.getParameter("path");
      mComponent  = req.getParameter("component");
    }

    public String   getEvent()          { return mEvent; }
    public String   getTarget()         { return mTarget; }
    public Boolean  getForce()          { return mForce; }
    public Integer  getSeq()            { return mSeq; }
    public Boolean  getJs()             { return mJs; }
    public String   getPath()           { return mPath; }
    public String   getComponent()      { return mComponent; }

    public void setEvent(String v)      { mEvent      = v; }
    public void setTarget(String v)     { mTarget     = v; }
    public void setForce(Boolean v)     { mForce      = v; }
    public void setSeq(Integer v)       { mSeq        = v; }
    public void setJs(Boolean v)        { mJs         = v; }
    public void setPath(String v)       { mPath       = v; }
    public void setComponent(String v)  { mComponent  = v; }

    private String toQueryParam(String name, String p) {
      return p != null ? name+"="+p : "";
    }
    private String toQueryParam(String name, Boolean p) {
      return p != null ? name+"="+p.toString() : "";
    }
    private String toQueryParam(String name, Integer p) {
      return p != null ? name+"="+p.toString() : "";
    }

    public String toQueryString() {
      String result = "";
      result += toQueryParam("event",     mEvent);
      result += toQueryParam("target",    mTarget);
      result += toQueryParam("force",     mForce);
      result += toQueryParam("golf",      mSeq);
      result += toQueryParam("js",        mJs);
      result += toQueryParam("path",      mPath);
      result += toQueryParam("component", mComponent);
      return result.length() > 0 ? "?"+result : "";
    }
  }

  /**
   * Contains state info for a golf request. This is what should be passed
   * around rather than the raw request or response.
   */
  public class GolfContext {
    
    public HttpServletRequest   request     = null;
    public HttpServletResponse  response    = null;
    public GolfParams           p           = null;
    public GolfSession          s           = null;
    public String               servletURL  = null;
    public String               urlHash     = null;
    /** FIXME which browser is the client using? FIXME */
    public BrowserVersion       browser     = BrowserVersion.FIREFOX_2;
    /** the jsvm for this request */
    public StoredJSVM           jsvm        = null;

    /**
     * Constructor.
     *
     * @param       request     the http request object
     * @param       response    the http response object
     */
    public GolfContext(HttpServletRequest request, 
        HttpServletResponse response) {
      this.request     = request;
      this.response    = response;
      this.p           = new GolfParams(request);
      this.s           = new GolfSession(request);
      this.urlHash     = request.getPathInfo();
      this.servletURL  = request.getRequestURL().toString()
                          .replaceFirst(";jsessionid=.*$", "");

      if (urlHash != null && urlHash.length() > 0) {
        urlHash    = urlHash.replaceFirst("/", "");
        servletURL = servletURL.replaceFirst("\\Q"+urlHash+"\\E$", "");
      } else {
        urlHash    = "";
      }

      this.jsvm = mJsvms.get(getSession().getId());

      if (this.jsvm == null) {
        this.jsvm = new StoredJSVM((WebClient) null);
        mJsvms.put(getSession().getId(), this.jsvm);
      }
    }

    public boolean hasEvent() {
      return (this.p.getEvent() != null && this.p.getTarget() != null);
    }

    public HttpSession getSession() {
      HttpSession result = request.getSession();
      return result;
    }

    public HttpSession getSession(boolean create) {
      HttpSession result = request.getSession(create);
      return result;
    }
  }

  /** htmlunit webclients for proxy-mode sessions */
  private static ConcurrentHashMap<String, StoredJSVM> mJsvms =
    new ConcurrentHashMap<String, StoredJSVM>();

  /** the amazon web services private key */
  private static String mAwsPrivate = null;

  /** the amazon web services public key */
  private static String mAwsPublic = null;

  /**
   * @see javax.servlet.Servlet#init(javax.servlet.ServletConfig)
   */
  public void init(ServletConfig config) throws ServletException {
    // tricky little bastard
    super.init(config);

    //mAwsPrivate  = config.getInitParameter("awsprivate");
    //mAwsPublic   = config.getInitParameter("awspublic");

    //try {
    //  String awsAccessKey = "0SFXC5HPSE5X6G94QFR2";
    //  String awsSecretKey = "x2PD9iVs+g6528piSLovvU2hTReX6rGcO0vJ5DIC";

    //  AWSCredentials awsCredentials = 
    //    new AWSCredentials(awsAccessKey, awsSecretKey);

    //  S3Service s3Service = new RestS3Service(awsCredentials);

    //  S3Bucket[] myBuckets = s3Service.listAllBuckets();
    //  System.out.println("How many buckets to I have in S3?");
    //  for (S3Bucket i : myBuckets)
    //    System.out.println("...." + i.getName()+"....");
    //} catch (Exception e) {
    //  e.printStackTrace();
    //}

    //GolfResource res = new GolfResource(getServletContext(), USER_AGENTS_FILE);
    //Document agents = 
    //  XmlUtil.buildDocument(
    //      new StringWebResponse(res.toString(), USER_AGENTS_FILE));
  }

  /**
   * Serve http requests!
   *
   * @param       request     the http request object
   * @param       response    the http response object
   */
  public void doGet(HttpServletRequest request, HttpServletResponse response)
    throws IOException, ServletException
  {
    GolfContext   context         = new GolfContext(request, response);
    String        result          = null;

    logRequest(context);

    // All query string parameters are considered to be arguments directed
    // to the golf container. The app itself gets its arguments in the path
    // info.

    try {
      if (!context.request.getPathInfo().endsWith("/"))
        throw new RedirectException(
            context.response.encodeURL(context.servletURL + "/"));

      if (context.p.getComponent() != null) {
        doComponentGet(context);
      } else if (context.p.getPath() != null) {
        doStaticResourceGet(context);
      } else {
        doDynamicResourceGet(context);
      }
    }

    catch (RedirectException r) {
      // send a 302 FOUND
      logResponse(context, 302);
      log(context, LOG_INFO, "302 ---to--> "+r.getMessage());
      context.response.sendRedirect(r.getMessage());
    }

    catch (FileNotFoundException e) {
      // send a 404 NOT FOUND
      logResponse(context, 404);
      errorPage(context, HttpServletResponse.SC_NOT_FOUND, e);
    }

    catch (Exception x) {
      // send a 500 INTERNAL SERVER ERROR
      x.printStackTrace();
      logResponse(context, 500);
      errorPage(context, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, x);
    }
  }

  /**
   * Do text processing of html to inject server/client specific things, etc.
   *
   * @param       page        the html page contents
   * @param       context     the golf request object
   * @param       server      whether to process for serverside or clientside
   * @return                  the processed page html contents
   */
  private String preprocess(String page, GolfContext context, boolean server) {
    String        sid       = context.getSession().getId();

    // pattern matching all script tags (should this be removed?)
    String pat2 = 
      "<script type=\"text/javascript\"[^>]*>([^<]|//<!\\[CDATA\\[)*</script>";

    // document type: xhtml
    String dtd = "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\"\n" +
      "\"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\">\n";

    if (!server)
      page = page.replaceFirst("^<\\?xml [^>]+>\n", "");

    // robots must not index event proxy (because infinite loops, etc.)
    if (!context.hasEvent())
      page = page.replaceFirst("noindex,nofollow", "index,follow");

    // remove the golfid attribute as it's not necessary on the client
    // and it is frowned upon by the w3c validator
    if (!server)
      page = page.replaceAll("(<[^>]+) golfid=['\"][0-9]+['\"]", "$1");

    if (! context.s.getJs().booleanValue() && !server) {
      // proxy mode only, so remove all javascript except on serverside
      page = page.replaceAll(pat2, "");
    } else {
      // on the client window.serverside must be false, and vice versa
      page = page.replaceFirst("(window.serverside +=) [a-zA-Z_]+;", 
          "$1 " + (server ? "true" : "false") + ";");

      // import the session ID into the javascript environment
      page = page.replaceFirst("(window.sessionid +=) \"[a-zA-Z_]+\";", 
          "$1 \"" + sid + "\";");
      
      // the servlet url (shenanigans here)
      page = page.replaceFirst("(window.servletURL +=) \"[a-zA-Z_]+\";", 
          "$1 \"" + context.servletURL + "\";");
      
      // the url fragment (shenanigans here)
      page = page.replaceFirst("(window.urlHash +=) \"[a-zA-Z_]+\";", 
          "$1 \"" + context.urlHash + "\";");
    }

    // no dtd for serverside because it breaks the xml parser
    return (server ? "" : dtd) + page;
  }

  /**
   * Show error page.
   *
   * @param     context   the golf request context
   * @param     e         the exception
   */
  public void errorPage(GolfContext context, int status, Exception e) {
    try {
      PrintWriter out = context.response.getWriter();

      context.response.setStatus(status);
      context.response.setContentType("text/html");

      out.println("<html><head><title>Golf Error</title></head><body>");
      out.println("<table height='100%' width='100%'>");
      out.println("<tr><td valign='middle' align='center'>");
      out.println("<table width='600px'>");
      out.println("<tr><td style='color:darkred;border:1px dashed red;" +
                  "background:#fee;padding:0.5em;font-family:monospace'>");
      out.println("<b>Golf error:</b> " + HTMLEntityEncode(e.getMessage()));
      out.println("</td></tr>");
      out.println("</table>");
      out.println("</td></tr>");
      out.println("</table>");
      out.println("</body></html>");
    } catch (Exception x) {
      x.printStackTrace();
    }
  }

  /**
   * Send a proxied response.
   *
   * @param   context       the golf context for this request
   */
  private void doProxy(GolfContext context) throws FileNotFoundException,
          IOException, URISyntaxException, RedirectException {
    String      sid     = context.getSession().getId();
    HtmlPage    result  = context.jsvm.lastPage;

    String      path    = context.request.getPathInfo().replaceFirst("^/+", "");
    String      event   = context.p.getEvent();
    String      target  = context.p.getTarget();
    WebClient   client  = context.jsvm.client;

    if (result == null || !path.equals(context.s.getLastURL())) {
      if (event != null && target != null && client != null) {
        String script = "jQuery(\"[golfid='"+target+"']\").click()";

        result = 
          (HtmlPage) client.getCurrentWindow().getEnclosedPage();

        result.executeJavaScript(script);
      } else if (client == null) {
        log(context, LOG_INFO, "INITIALIZING NEW CLIENT");
        client = new WebClient(context.browser);

        // write any alert() calls to the log
        client.setAlertHandler(new AlertHandler() {
          public void handleAlert(Page page, String message) {
            Log.info("ALERT: " + message);
          }
        });

        // if this isn't long enough it'll timeout before all ajax is complete
        client.setJavaScriptTimeout(JSVM_TIMEOUT);

        context.jsvm.client = client;

        // the blank skeleton html template
        String newHtml = 
          (new GolfResource(getServletContext(), FILE_NEW_HTML)).toString();

        // do not pass query string to the app, as those parameters are meant
        // only for the golf container itself.

        StringWebResponse response = new StringWebResponse(
          preprocess(newHtml, context, true),
          new URL(context.servletURL + "#" + context.urlHash)
        );

        // run it through htmlunit
        result = (HtmlPage) context.jsvm.client.loadWebResponseInto(
          response,
          client.getCurrentWindow()
        );
      } else {
        String script = "jQuery.history.load('"+context.urlHash+"');";
        result = (HtmlPage) client.getCurrentWindow().getEnclosedPage();
        result.executeJavaScript(script);
      }

      Iterator<HtmlAnchor> anchors = result.getAnchors().iterator();
      while (anchors.hasNext()) {
        HtmlAnchor a = anchors.next();
        a.setAttribute("href",context.response.encodeURL(a.getHrefAttribute()));
      }

      String loc = (String) result.executeJavaScript("jQuery.golf.location")
                              .getJavaScriptResult();
      
      if (!loc.equals(path) || context.request.getQueryString() != null) {
        context.jsvm.lastPage = result;
        context.s.setLastURL(loc);
        throw new RedirectException(
            context.response.encodeURL(context.servletURL + loc));
      }
    }

    String html = result.asXml();

    if (context.jsvm.lastPage != null) {
      context.jsvm.lastPage = null;
      context.s.setLastURL(null);
    }

    sendResponse(context, preprocess(html, context, false), "text/html", false);
  }

  /**
   * Send a non-proxied response.
   *
   * @param   context       the golf context for this request
   */
  private void doNoProxy(GolfContext context) throws Exception {
    // the blank skeleton html template
    String html = 
      (new GolfResource(getServletContext(), FILE_NEW_HTML)).toString();

    sendResponse(context, preprocess(html, context, false), "text/html", true);
  }

  /**
   * Do the request flowchart.
   *
   * @param   context       the golf context for this request
   */
  private void doDynamicResourceGet(GolfContext context) throws Exception {

    HttpSession session     = context.getSession();
    String      remoteAddr  = context.request.getRemoteAddr();
    String      sessionAddr = context.s.getIpAddr();

    if (! session.isNew()) {
      if (context.p.getForce() != null && context.p.getForce().booleanValue())
        context.s.setSeq(0);

      int seq = (context.s.getSeq() == null ? 0 : (int) context.s.getSeq());
      
      context.s.setSeq(++seq);

      if (sessionAddr != null && sessionAddr.equals(remoteAddr)) {
        if (seq == 1) {
          if (context.p.getJs() != null) {
            context.s.setJs(context.p.getJs());

            String uri = context.request.getRequestURI();
            if (context.request.isRequestedSessionIdFromCookie())
              uri = uri.replaceAll(";jsessionid=.*$", "");
            
            throw new RedirectException(uri);
          }
        } else if (seq >= 2) {
          if (context.s.getJs() != null) {
            if (context.s.getJs().booleanValue())
              doNoProxy(context);
            else
              doProxy(context);
            return;
          }
        }
      }

      session.invalidate();
      session = context.getSession(true);
    }

    context.s.setSeq(0);
    context.s.setIpAddr(remoteAddr);

    String jsDetect = 
      (new GolfResource(getServletContext(), FILE_JSDETECT_HTML)).toString();

    jsDetect = jsDetect.replaceAll("__HAVE_JS__", 
        context.response.encodeURL("?js=true"));
    jsDetect = jsDetect.replaceAll("__DONT_HAVE_JS__", 
        context.response.encodeURL("?js=false"));

    sendResponse(context, jsDetect, "text/html", false);
  }

  private void sendResponse(GolfContext context, String html, 
      String contentType, boolean canCache) throws IOException {
    context.response.setContentType(contentType);

    if (canCache)
      setCachable(context);

    PrintWriter out = context.response.getWriter();
    out.println(html);
    out.close();
    logResponse(context, 200);
  }

  /**
   * Handle a request for a component's html/js/css.
   *
   * @param   context       the golf context for this request
   */
  private void doComponentGet(GolfContext context) 
      throws FileNotFoundException, IOException, JSONException {
    String classPath = context.p.getComponent();
    String className = classPath.replace('.', '-');
    String path      = "/components/" + classPath.replace('.', '/');


    String html = path + ".html";
    String css  = path + ".css";
    String js   = path + ".js";

    GolfResource htmlRes = new GolfResource(getServletContext(), html);
    GolfResource cssRes  = new GolfResource(getServletContext(), css);
    GolfResource jsRes   = new GolfResource(getServletContext(), js);

    String htmlStr = 
      processComponentHtml(context, htmlRes.toString(), className);
    String cssStr = 
      processComponentCss(context, cssRes.toString(), className);
    String jsStr = jsRes.toString();

    String json = new JSONStringer()
      .object()
        .key("name")
        .value(classPath)
        .key("html")
        .value(htmlStr)
        .key("css")
        .value(cssStr)
        .key("js")
        .value(jsStr)
      .endObject()
      .toString();

    json = "jQuery.golf.doJSONP(" + json + ");";

    sendResponse(context, json, "text/javascript", true);
  }

  /**
   * Process html file for service, inserting component class name, etc.
   *
   * @param   context       the golf context for this request
   * @param   text          the component css/html text
   * @param   className     the text class name
   * @return                the processed css/html text
   */
  private String processComponentHtml(GolfContext context, String text, 
      String className) {
    String path   = context.p.getPath();
    String result = text;

    // Add the unique component css class to the component outermost
    // element.

    // the first opening html tag
    String tmp = result.substring(0, result.indexOf('>'));

    // add the component magic classes to the tag
    if (tmp.matches(".*['\"\\s]class\\s*=\\s*['\"].*"))
      result = 
        result.replaceFirst("^(.*class\\s*=\\s*.)", "$1component " + 
            className + " ");
    else
      result = 
        result.replaceFirst("(<[a-zA-Z]+)", "$1 class=\"component " + 
            className + "\"");

    return result;
  }

  /**
   * Process css file for service, inserting component class name, etc.
   *
   * @param   context       the golf context for this request
   * @param   text          the component css/html text
   * @param   className     the text class name
   * @return                the processed css/html text
   */
  private String processComponentCss(GolfContext context, String text,
      String className) {
    String path       = context.p.getPath();
    String result     = text;

    // Localize this css file by inserting the unique component css class
    // in the beginning of every selector. Also remove extra whitespace and
    // comments, etc.

    // remove newlines
    result = result.replaceAll("[\\r\\n\\s]+", " ");
    // remove comments
    result = result.replaceAll("/\\*.*\\*/", "");
    // this is bad but fuckit
    result = 
      result.replaceAll("(^|\\})\\s*([^{]*[^{\\s])*\\s*\\{", "$1 ." + 
          className + " $2 {");
    result = result.trim();

    return result;
  }

  private void setCachable(GolfContext context) {
    long currentTime = System.currentTimeMillis();
    long later       = 24*60*60*1000; // a day (milliseconds)
    context.response.setDateHeader("Expires", currentTime + later);
    context.response.setHeader("Cache-Control", "max-age=3600,public");
  }

  /**
   * Handle a request for a static resource.
   *
   * @param   context       the golf context for this request
   */
  private void doStaticResourceGet(GolfContext context) 
      throws FileNotFoundException, IOException, JSONException {
    String path = context.p.getPath();

    if (! path.startsWith("/"))
      path = "/" + path;

    GolfResource res = new GolfResource(getServletContext(), path);

    context.response.setContentType(res.getMimeType());
    setCachable(context);

    if (res.getMimeType().startsWith("text/")) {
      PrintWriter out = context.response.getWriter();
      out.println(res.toString());
    } else {
      OutputStream out = context.response.getOutputStream();
      out.write(res.toByteArray());
    }

    logResponse(context, 200);
  }

  /**
   * Format a nice log message.
   *
   * @param     context     the golf context for this request
   * @param     s           the log message
   * @return                the formatted log message
   */
  private String fmtLogMsg(GolfContext context, String s) {
    String sid = context.getSession().getId();
    String ip  = context.request.getRemoteHost();
    return (sid != null ? "[" + sid.toUpperCase().replaceAll(
          "(...)(?=...)", "$1.") + "] " : "") + ip + "\n" + s;
  }

  /**
   * Send a formatted message to the logs.
   *
   * @param     context     the golf context for this request
   * @param     level       the severity of the message (LOG_DEBUG to LOG_WARN)
   * @param     s           the log message
   */
  public void log(GolfContext context, int level, String s) {
    switch(level) {
      case LOG_DEBUG:   Log.debug (fmtLogMsg(context, s));    break;
      case LOG_INFO:    Log.info  (fmtLogMsg(context, s));    break;
      case LOG_WARN:    Log.warn  (fmtLogMsg(context, s));    break;
    }
  }

  /**
   * Logs a http servlet request.
   *
   * @param     context     the golf context for this request
   */
  private void logRequest(GolfContext context) {
    String method = context.request.getMethod();
    String path   = context.request.getPathInfo();
    String query  = context.request.getQueryString();
    String host   = context.request.getRemoteHost();
    String sid    = context.getSession().getId();

    String line   = method + " " + path + (query != null ? "?" + query : "");

    log(context, LOG_INFO, line);

    //System.out.println("||||||||||||||||||||||||||||||||||||||");
    //Enumeration headerNames = context.request.getHeaderNames();
    //while(headerNames.hasMoreElements()) {
    //  String headerName = (String)headerNames.nextElement();
    //  System.out.println("||||||||||| " + headerName + ": "
    //    + context.request.getHeader(headerName));
    //}
    //System.out.println("||||||||||||||||||||||||||||||||||||||");

  }

  /**
   * Logs a http servlet response.
   *
   * @param     context     the golf context for this request
   */
  private void logResponse(GolfContext context, int status) {
    String method = String.valueOf(status);
    String path   = context.request.getPathInfo();
    String query  = context.request.getQueryString();
    String host   = context.request.getRemoteHost();
    String sid    = context.getSession().getId();

    String line   = method + " " + path + (query != null ? "?" + query : "");

    log(context, LOG_INFO, line);
  }

  /**
   * Convenience function to do html entity encoding.
   *
   * @param     s         the string to encode
   * @return              the encoded string
   */
  public static String HTMLEntityEncode(String s) {
    StringBuffer buf = new StringBuffer();
    int len = (s == null ? -1 : s.length());

    for ( int i = 0; i < len; i++ ) {
      char c = s.charAt( i );
      if ( c>='a' && c<='z' || c>='A' && c<='Z' || c>='0' && c<='9' ) {
        buf.append( c );
      } else {
        buf.append("&#" + (int)c + ";");
      }
    }

    return buf.toString();
  }
}
