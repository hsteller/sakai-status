// StatusServlet.java
//   Reports various Sakai and Tomcat status information
//
// Created 2012-06-13 daveadams@gmail.com
// Last updated 2012-06-13 daveadams@gmail.com
//
// https://github.com/daveadams/sakai-status
//
// This software is public domain. See LICENSE for more information.
//
package org.sakaiproject.status;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Consumer;

import javax.management.JMX;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;
import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.dbcp.BasicDataSource;
import org.sakaiproject.authz.api.FunctionManager;
import org.sakaiproject.component.cover.ComponentManager;
import org.sakaiproject.event.api.UsageSession;
import org.sakaiproject.event.api.UsageSessionService;
import org.sakaiproject.tool.api.SessionManager;
import org.sakaiproject.tool.api.Tool;
import org.sakaiproject.tool.api.ToolManager;
import org.sakaiproject.user.api.UserDirectoryService;
import org.sakaiproject.user.api.UserNotDefinedException;
import org.sakaiproject.util.SakaiProperties;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.config.CacheConfiguration;
import net.sf.ehcache.config.PersistenceConfiguration;

@SuppressWarnings("serial")
public class StatusServlet extends HttpServlet
{
	protected MBeanServer mbs;


	public static List<String> endpoints;
	private final Map<String,Consumer<PrintWriter>> ENDPOINTS_MAP  = new HashMap<>();
	
	public void init() throws ServletException
	{
		mbs = ManagementFactory.getPlatformMBeanServer();
		
		ENDPOINTS_MAP.put("/tomcat/mbeans", this::reportAllMBeans);
		ENDPOINTS_MAP.put("/tomcat/mbeans/details", this::reportAllMBeanDetails);
		ENDPOINTS_MAP.put("/tomcat/mbeans/domains", this::reportMBeanDomains);
		ENDPOINTS_MAP.put("/tomcat/current/uris", this::reportCurrentURIs);
		ENDPOINTS_MAP.put("/tomcat/threads", this::reportThreadPoolStatus);
		ENDPOINTS_MAP.put("/tomcat/threads/details", this::reportThreadDetails);
		ENDPOINTS_MAP.put("/tomcat/threads/stacks", this::reportThreadStackTraces);
		ENDPOINTS_MAP.put("/tomcat/threadgroups", this::reportThreadGroups);
		ENDPOINTS_MAP.put("/tomcat/webapps", this::reportWebappStatus);
		ENDPOINTS_MAP.put("/tomcat/webapps/details", this::reportDetailedWebappStatus);
		ENDPOINTS_MAP.put("/system/memory", this::reportMemoryStatus);
		ENDPOINTS_MAP.put("/system/properties", this::reportSystemProperties);
		ENDPOINTS_MAP.put("/sakai/database", this::reportSakaiDatabaseStatus);
		ENDPOINTS_MAP.put("/sakai/beans", this::reportSakaiBeans);
		ENDPOINTS_MAP.put("/sakai/sessions", this::reportActiveSessionCounts);
		ENDPOINTS_MAP.put("/sakai/sessions/counts", this::reportAllSessionCounts);
		ENDPOINTS_MAP.put("/sakai/sessions/total", this::reportAllSessionTotal);
		ENDPOINTS_MAP.put("/sakai/sessions/users-by-server", this::reportUsersByServer);
		ENDPOINTS_MAP.put("/sakai/sessions/all-users", this::reportAllUsers);
		ENDPOINTS_MAP.put("/sakai/properties", this::reportSakaiProperties);
		ENDPOINTS_MAP.put("/sakai/tools", this::reportAllTools);
		ENDPOINTS_MAP.put("/sakai/functions", this::reportAllFunctions);
		ENDPOINTS_MAP.put("/sakai/cache", this::reportCacheList);
		
		
		
		if (endpoints == null){
			endpoints = new ArrayList<>(ENDPOINTS_MAP.keySet());
			Collections.sort(endpoints);
		}
	}

	
	 // "request.getPathInfo()" returns null because the servlet is mapped as "/" in the web.xml;
	// getPathInfo only returns useful info if the request mapping ends in "/*" 
	private static final String getPathInfo(HttpServletRequest request){		
		final String URI = request.getRequestURI();
		final String CTX = request.getContextPath();
		return URI.substring(CTX.length());			
	}
	
	public void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		String path = getPathInfo(request);
				
		if ("/".equals(path) || path == null){		
			request.getRequestDispatcher("/endpoints.jsp").forward(request, response);
			return;
		}
		
		response.setContentType("text/plain");
		try (PrintWriter pw = response.getWriter()){		
			Consumer<PrintWriter> function = ENDPOINTS_MAP.get(path);
			if (function != null){
				function.accept(pw);
			}
			else if (path.startsWith("/sakai/tools/")){
				reportToolDetails(path.replace("/sakai/tools/",""), pw);
			}
			else if(path.startsWith("/sakai/cache/")) {
				reportCacheDetails(path.replace("/sakai/cache/",""), pw);
			}
		}
		catch(Throwable e) {
			if (e instanceof WrappedException){
				e = e.getCause();
			}
			System.err.println("Exception: "+e.getMessage());
			e.printStackTrace();
			response.getWriter().print("Exception: " + e.getMessage() + "\n");
		}      

	}

	protected Set<ObjectName> findMBeans(String searchString)
	{
		try {
			return mbs.queryNames(new ObjectName(searchString), null);
		} catch(Exception e) {
			return null;
		}
	}


	protected void reportThreadPoolStatus(PrintWriter pw) 
	{
		try {
			for(ObjectName tpName : findMBeans("*:type=ThreadPool,*")) {
				pw.print(mbs.getAttribute(tpName, "name") + ",");
				pw.print(mbs.getAttribute(tpName, "maxThreads") + ",");
				pw.print(mbs.getAttribute(tpName, "currentThreadCount") + ",");
				pw.print(mbs.getAttribute(tpName, "currentThreadsBusy") + "\n");
			}
		}
		catch (Exception e){
			throw new WrappedException(e);
		}
	}

	protected void reportThreadGroups(PrintWriter pw) 
	{
		printThreadGroupDetails(findSystemThreadGroup(), "", pw);
	}

	protected void printThreadGroupDetails(ThreadGroup g, String indent, PrintWriter pw) 
	{

		ThreadGroup parent = g.getParent();
		String parentName = "";
		if(parent != null) {
			parentName = parent.getName();
		}

		int threadCount = g.activeCount();
		int groupCount = g.activeGroupCount();

		pw.print(indent + g.getName() + "," + parentName + "," +
				threadCount + "," + groupCount + "\n");

		if(groupCount > 0) {
			ThreadGroup[] children = new ThreadGroup[groupCount];
			g.enumerate(children, false);

			for(ThreadGroup child : children) {
				if(child != null) {
					printThreadGroupDetails(child, indent + "  ", pw);
				}
			}
		}
	}

	protected void reportThreadDetails(PrintWriter pw) 
	{
		for(Thread thread : findAllThreads()) {
			if(thread != null) {
				String threadLocation = "";
				try {
					StackTraceElement ste = thread.getStackTrace()[0];
					StackTraceElement ste2 = thread.getStackTrace()[1];
					threadLocation =
							ste.getClassName() + "." +
									ste.getMethodName() + "()," +
									ste.getFileName() + ":" +
									ste.getLineNumber() + "," +
									ste2.getClassName() + "." +
									ste2.getMethodName() + "()," +
									ste2.getFileName() + ":" +
									ste2.getLineNumber();
				} catch(Exception e) {
					threadLocation = "?,?,?,?";
				}
				pw.print(thread.getThreadGroup().getName() + "," +
						thread.getId() + "," +
						thread.getName() + "," +
						thread.getPriority() + "," +
						thread.getState().name() + "," +
						(thread.isAlive() ? "" : "notalive") + "," +
						(thread.isDaemon() ? "daemon" : "") + "," +
						(thread.isInterrupted() ? "interrupted" : "") + "," +
						threadLocation + "\n");
			}
		}
	}

	protected ThreadGroup findSystemThreadGroup() 
	{
		// find the master threadgroup
		ThreadGroup group = Thread.currentThread().getThreadGroup();
		ThreadGroup parent = null;
		while((parent = group.getParent()) != null) {
			group = parent;
		}
		return group;
	}

	protected Thread[] findAllThreads() 
	{
		ThreadGroup group = findSystemThreadGroup();
		Thread[] threads = new Thread[group.activeCount()];
		group.enumerate(threads);

		return threads;
	}

	protected void reportThreadStackTraces(PrintWriter pw) 
	{


		for(Thread thread : findAllThreads()) {
			if(thread != null) {
				String stackTrace = "";
				try {
					StackTraceElement[] stack = thread.getStackTrace();
					for(StackTraceElement ste : stack) {
						stackTrace +=
								ste.getClassName() + "." +
										ste.getMethodName() + "();" +
										ste.getFileName() + ":" +
										ste.getLineNumber() + " ";
					}
				} catch(Exception e) {
					stackTrace += "-";
				}
				pw.print(thread.getThreadGroup().getName() + " " +
						thread.getId() + " " +
						stackTrace + "\n");
			}
		}
	}

	protected void reportWebappStatus(PrintWriter pw) 
	{
		try {
			for(ObjectName appName : findMBeans("*:j2eeType=WebModule,*")) {
				pw.print(mbs.getAttribute(appName, "docBase") + ",");
				pw.print(mbs.getAttribute(appName, "processingTime") + "\n");
			}
		}
		catch (Exception e){
			throw new WrappedException(e);
		}
	}

	protected void reportDetailedWebappStatus(PrintWriter pw) 
	{
		try{

			for(ObjectName appName : findMBeans("*:j2eeType=WebModule,*")) {
				for(MBeanAttributeInfo mbai : mbs.getMBeanInfo(appName).getAttributes()) {
					pw.print(mbai.getName() + ",");
					pw.print(mbai.getType() + ",");
					pw.print(mbai.getDescription() + ",");
					pw.print(mbs.getAttribute(appName, mbai.getName()) + "\n");
				}
				pw.print("\n");
				for(MBeanOperationInfo mboi : mbs.getMBeanInfo(appName).getOperations()) {
					pw.print(mboi.getName() + ",");
					pw.print(mboi.getReturnType() + ",");
					pw.print(mboi.getDescription() + "\n");
				}
				pw.print("\n\n");
			}
		}
		catch (Exception e){
			throw new WrappedException(e);
		}
	}

	protected void reportCurrentURIs(PrintWriter pw) 
	{
		try {
			Object currentUri = null;
			for(ObjectName rpName : findMBeans("*:type=RequestProcessor,*")) {
				currentUri = mbs.getAttribute(rpName, "currentUri");
				if(currentUri != null) {
					pw.print(mbs.getAttribute(rpName, "workerThreadName") + " " + currentUri + "\n");
				}
			}
		}
		catch (Exception e){
			throw new WrappedException(e);
		}
	}

	protected void reportAllMBeans(PrintWriter pw) 
	{


		Set<ObjectInstance> allBeans = mbs.queryMBeans(null, null);
		SortedSet<String> sortedBeanNames = new TreeSet<String>();
		for(ObjectInstance bean : allBeans) {
			sortedBeanNames.add(bean.getObjectName().toString());
		}
		for(Object beanName : sortedBeanNames) {
			pw.print(beanName + "\n");
		}
	}

	protected void reportAllMBeanDetails(PrintWriter pw) 
	{

		try {
			Set<ObjectInstance> allBeans = mbs.queryMBeans(null, null);
			SortedSet<String> sortedBeanNames = new TreeSet<String>();
			for(ObjectInstance bean : allBeans) {
				sortedBeanNames.add(bean.getObjectName().toString());
			}
			for(Object beanName : sortedBeanNames) {
				pw.print(beanName.toString() + "\n");
				ObjectName beanObjectName = new ObjectName(beanName.toString());
				for(MBeanAttributeInfo mbai : mbs.getMBeanInfo(beanObjectName).getAttributes()) {
					pw.print("  ");
					pw.print(mbai.getName() + ",");
					pw.print(mbai.getType() + ",");
					pw.print(mbai.getDescription() + ",");
					try {
						pw.print(mbs.getAttribute(beanObjectName, mbai.getName()) + "\n");
					}
					catch (Exception e2){
						System.err.println("error getting attribute: "+mbai.getName()+" for "+beanObjectName+": ");
						System.err.println("\t"+e2.getClass()+": "+e2.getMessage());
					}
				}
				pw.print("\n");
				for(MBeanOperationInfo mboi : mbs.getMBeanInfo(beanObjectName).getOperations()) {
					pw.print("  ");
					pw.print(mboi.getReturnType() + ",");
					pw.print(mboi.getName() + "(");
					for(MBeanParameterInfo mbpi : mboi.getSignature()) {
						pw.print(mbpi.getType() + " " + mbpi.getName() + ",");
					}
					pw.print("),");
					pw.print(mboi.getDescription() + "\n");
				}
				pw.print("\n-----------------------------\n\n");
			}
		}
		catch (Exception e){
			throw new WrappedException(e);
		}
	}

	protected void reportMBeanDomains(PrintWriter pw) 
	{


		pw.print("default: " + mbs.getDefaultDomain() + "\n");
		pw.print("domains:\n");
		for(String domain : mbs.getDomains()) {
			pw.print("  - " + domain + "\n");
		}
	}

	protected void reportMemoryStatus(PrintWriter pw) 
	{


		pw.print(Runtime.getRuntime().freeMemory() + ",");
		pw.print(Runtime.getRuntime().totalMemory() + ",");
		pw.print(Runtime.getRuntime().maxMemory() + "\n");
	}

	protected void reportSakaiDatabaseStatus(PrintWriter pw) 
	{
		
		Object ds = ComponentManager.get("javax.sql.DataSource");
		if(ds == null) {
			throw new RuntimeException("No data source found.");
		}
		int activeConnections=-1;
		int idleConections=-1; 
		try {
			if (ds instanceof BasicDataSource) {
				BasicDataSource db = (BasicDataSource) ds;
				activeConnections = db.getNumActive();
				idleConections = db.getNumIdle();
	
			}
			else if (ds instanceof HikariDataSource) {
				@SuppressWarnings("resource")
				HikariDataSource db = (HikariDataSource) ds;			
				//db.setRegisterMbeans(true);	
				ObjectName poolName = new ObjectName("com.zaxxer.hikari:type=Pool ("+db.getPoolName()+")");
				HikariPoolMXBean poolProxy = JMX.newMXBeanProxy(mbs, poolName, HikariPoolMXBean.class);
				activeConnections = poolProxy.getActiveConnections();
				idleConections = poolProxy.getIdleConnections();
	
			}
			else {
				pw.print("Unsupported datasourse implementation: "+ds.getClass()+"\n");
				return;
			}
			pw.print(activeConnections + "," + idleConections + "\n");
		}
		catch (Exception e){
			throw new WrappedException(e);
		}
	}

	protected void reportSakaiBeans(PrintWriter pw) 
	{


		SortedSet<String> sortedBeanNames = new TreeSet<String>();
		for(Object beanName : ComponentManager.getRegisteredInterfaces()) {
			sortedBeanNames.add(beanName.toString());
		}
		for(Object beanName : sortedBeanNames) {
			pw.print(beanName + "\n");
		}
	}

	protected void reportActiveSessionCounts(PrintWriter pw) 
	{
		SessionManager sm = (SessionManager)ComponentManager.get("org.sakaiproject.tool.api.SessionManager");
		if(sm == null) {
			throw new RuntimeException("Could not get SessionManager bean.");
		}

		// count sessions in the last hour, half-hour, 15 minutes, five minutes
		pw.print(sm.getActiveUserCount(3600) + ",");
		pw.print(sm.getActiveUserCount(1800) + ",");
		pw.print(sm.getActiveUserCount(900) + ",");
		pw.print(sm.getActiveUserCount(300) + "\n");
	}

	protected void reportAllSessionCounts(PrintWriter pw) 
	{       
		Map<String,Collection<UsageSession>> sessionsByServer = getSessionsByServer();
		int total = 0;
		for(String key : sessionsByServer.keySet()) {
			Collection<UsageSession> serverSessions = sessionsByServer.get(key);
			String serverName = ((String)key).replaceAll("-[0-9]+$", "");
			pw.print(serverName + ": " + serverSessions.size() + "\n");
			total += serverSessions.size();
		}
		pw.print("total: " + total + "\n");
	}

	protected void reportAllSessionTotal(PrintWriter pw) 
	{        
		Map<String,Collection<UsageSession>> sessionsByServer = getSessionsByServer();
		int total = 0;
		for(String key : sessionsByServer.keySet()) {
			Collection<UsageSession> serverSessions = sessionsByServer.get(key);
			total += serverSessions.size();
		}
		pw.print(total + "\n");
	}


	protected void reportUsersByServer(PrintWriter pw) 
	{

		UserDirectoryService uds = (UserDirectoryService)ComponentManager.get("org.sakaiproject.user.api.UserDirectoryService");
		if(uds == null) {
			throw new RuntimeException("Could not get UserDirectoryService bean.");
		}

		Map<String,Collection<UsageSession>> sessionsByServer = getSessionsByServer();
		for(Object key : sessionsByServer.keySet()) {
			Collection<UsageSession> serverSessions = sessionsByServer.get(key);
			String serverName = ((String)key).replaceAll("-[0-9]+$", "");
			pw.print(serverName + ":" + "\n");
			for(UsageSession sessionInfo : serverSessions) {
				String userId = sessionInfo.toString().split(" ")[4];
				String eid;
				try {                	
					eid = uds.getUser(userId).getDisplayId();
				}
				catch (UserNotDefinedException e) {
					eid = "no display ID for userId \""+userId+"\"";
				}
				pw.print("  - " + eid + "\n");
			}
		}
	}

	protected void reportAllUsers(PrintWriter pw) 
	{


		UserDirectoryService uds = (UserDirectoryService)ComponentManager.get("org.sakaiproject.user.api.UserDirectoryService");
		if(uds == null) {
			throw new RuntimeException("Could not get UserDirectoryService bean.");
		}

		Map<String,Collection<UsageSession>> sessionsByServer = getSessionsByServer();
		for(Object key : sessionsByServer.keySet()) {
			Collection<UsageSession> serverSessions = sessionsByServer.get(key);
			String serverName = ((String)key).replaceAll("-[0-9]+$", "");
			for(Object sessionInfo : serverSessions) {
				pw.print(serverName + ":" + "\n");
				String userId = sessionInfo.toString().split(" ")[4];
				String eid;
				try {
					eid = uds.getUser(userId).getDisplayId();
					pw.print(eid + "\n");
				}
				catch (UserNotDefinedException e) {
					pw.print("no display ID for userId \""+userId+"\"\n");
				}
			}
		}
	}

	protected void reportSystemProperties(PrintWriter pw) 
	{


		Properties props = System.getProperties();
		Enumeration<?> propNames = props.propertyNames();
		SortedSet<String> sortedPropNames = new TreeSet<String>();
		while(propNames.hasMoreElements()) {
			sortedPropNames.add((String)propNames.nextElement());
		}

		for(Object pName : sortedPropNames) {
			String propertyName = (String)pName;
			String value = props.getProperty(propertyName);
			if(propertyName.startsWith("password")) {
				value = "********";
			}
			pw.print(propertyName + "=" + value + "\n");
		}
	}

	protected void reportSakaiProperties(PrintWriter pw) 
	{
		SakaiProperties sp = (SakaiProperties)ComponentManager.get("org.sakaiproject.component.SakaiProperties");
		if(sp == null) {
			throw new RuntimeException("Could not get SakaiProperties bean.");
		}

		Properties props = sp.getRawProperties();
		Enumeration<?> propNames = props.propertyNames();
		SortedSet<String> sortedPropNames = new TreeSet<String>();
		while(propNames.hasMoreElements()) {
			sortedPropNames.add((String)propNames.nextElement());
		}

		for(Object pName : sortedPropNames) {
			String propertyName = (String)pName;
			String value = props.getProperty(propertyName);
			if(propertyName.startsWith("password") || propertyName.endsWith("password")) {
				value = "********";
			}
			pw.print(propertyName + "=" + value + "\n");
		}
	}

	protected void reportAllTools(PrintWriter pw) 
	{
		ToolManager tm = (ToolManager)ComponentManager.get("org.sakaiproject.tool.api.ActiveToolManager");
		if(tm == null) {
			throw new RuntimeException("Could not get ToolManager bean.");
		}

		SortedSet<String> sortedToolIds = new TreeSet<String>();
		Set<Tool> toolSet = tm.findTools(null, null);
		for(Tool tool : toolSet) {
			sortedToolIds.add(tool.getId());
		}

		for(String toolId : sortedToolIds) {
			pw.print(toolId + "\n");
		}
	}

	protected void reportToolDetails(String toolId, PrintWriter pw) 
	{


		ToolManager tm = (ToolManager)ComponentManager.get("org.sakaiproject.tool.api.ActiveToolManager");
		if(tm == null) {
			throw new RuntimeException("Could not get ToolManager bean.");
		}

		Tool tool = tm.getTool(toolId);
		if(tool == null) {
			pw.print("ERROR: no such tool ID\n");
			return;
		}

		pw.print("id: " + tool.getId() + "\n");
		pw.print("title: " + tool.getTitle() + "\n");
		pw.print("description: " + tool.getDescription() + "\n");

		Properties regProps = tool.getRegisteredConfig();
		Enumeration<?> propNames = regProps.propertyNames();
		SortedSet<String> sortedPropNames = new TreeSet<String>();
		while(propNames.hasMoreElements()) {
			sortedPropNames.add((String)propNames.nextElement());
		}
		if(sortedPropNames.size() > 0) {
			pw.print("registered_properties:\n");
			for(Object pName : sortedPropNames) {
				String propertyName = (String)pName;
				String value = regProps.getProperty(propertyName);
				pw.print("  " + propertyName + ": " + value + "\n");
			}
		}

		Properties mutableProps = tool.getMutableConfig();
		propNames = mutableProps.propertyNames();
		sortedPropNames = new TreeSet<String>();
		while(propNames.hasMoreElements()) {
			sortedPropNames.add((String)propNames.nextElement());
		}
		if(sortedPropNames.size() > 0) {
			pw.print("mutable_properties:\n");
			for(Object pName : sortedPropNames) {
				String propertyName = (String)pName;
				String value = mutableProps.getProperty(propertyName);
				pw.print("  " + propertyName + ": " + value + "\n");
			}
		}

		Properties finalProps = tool.getFinalConfig();
		propNames = finalProps.propertyNames();
		sortedPropNames = new TreeSet<String>();
		while(propNames.hasMoreElements()) {
			sortedPropNames.add((String)propNames.nextElement());
		}
		if(sortedPropNames.size() > 0) {
			pw.print("final_properties:\n");
			for(String pName : sortedPropNames) {
				String propertyName = pName;
				String value = finalProps.getProperty(propertyName);
				pw.print("  " + propertyName + ": " + value + "\n");
			}
		}

		Set<String> keywords = tool.getKeywords();
		if(keywords != null) {
			if(keywords.size() > 0) {
				pw.print("keywords:\n");
				for(String keyword : keywords) {
					pw.print("  - " + keyword + "\n");
				}
			}
		}

		Set<String> categories = tool.getCategories();
		if(categories != null) {
			if(categories.size() > 0) {
				pw.print("categories:\n");
				for(String category : categories) {
					pw.print("  - " + category + "\n");
				}
			}
		}
	}

	protected void reportAllFunctions(PrintWriter pw) 
	{


		FunctionManager fm = (FunctionManager)ComponentManager.get("org.sakaiproject.authz.api.FunctionManager");
		if(fm == null) {
			throw new RuntimeException("Could not get FunctionManager bean.");
		}

		SortedSet<String> sortedFunctionNames = new TreeSet<String>();
		List<String> functionList = fm.getRegisteredFunctions();
		for(String fname : functionList) {
			sortedFunctionNames.add(fname);
		}

		for(String functionName : sortedFunctionNames) {
			pw.print(functionName + "\n");
		}
	}

	protected void reportCacheList(PrintWriter pw) 
	{


		CacheManager manager = (CacheManager)ComponentManager.get("org.sakaiproject.memory.api.MemoryService.cacheManager");

		String[] cacheNames = manager.getCacheNames();
		Arrays.sort(cacheNames);
		for (String cacheName : cacheNames) {
			pw.print(cacheName + "\n");
		}
	}

	protected void reportCacheDetails(String cacheName, PrintWriter pw) 
	{        

		CacheManager manager = (CacheManager)ComponentManager.get("org.sakaiproject.memory.api.MemoryService.cacheManager");

		if(manager == null) {
			throw new RuntimeException("Could not get CacheManager bean.");
		}

		Cache cache = manager.getCache(cacheName);
		if(cache == null) {
			throw new RuntimeException("No such cache name.");
		}

		String evictionPolicy = cache.getMemoryStoreEvictionPolicy().getName();
		CacheConfiguration config = cache.getCacheConfiguration();
		long maxObjects = config.getMaxEntriesLocalHeap();
		long ttl = config.getTimeToLiveSeconds();
		long tti = config.getTimeToIdleSeconds();
		boolean eternal = config.isEternal();

		// boolean overflowToDisk = config.isOverflowToDisk();
		PersistenceConfiguration.Strategy persistenceStrategy = config.getPersistenceConfiguration().getStrategy();

		net.sf.ehcache.Statistics stats = cache.getStatistics();
		long objectCount = stats.getObjectCount();
		long hits = stats.getCacheHits();
		long misses = stats.getCacheMisses();
		long evictions = stats.getEvictionCount();
		float latency = stats.getAverageGetTime();
		long total = hits + misses;
		long hitRatio = ((total > 0) ? ((100l * hits) / total) : 0);

		pw.print("name: " + cache.getName() + "\n");
		pw.print("memory: " + cache.calculateInMemorySize() + "\n");
		pw.print("objects: " + objectCount + "\n");
		pw.print("maxobjects: " + maxObjects + "\n");
		pw.print("time-to-live: " + ttl + "\n");
		pw.print("time-to-idle: " + tti + "\n");
		pw.print("eviction-policy: " + evictionPolicy + "\n");
		pw.print("eternal: " + eternal + "\n");
		//        pw.print("overflow-to-disk: " + overflowToDisk + "\n");
		pw.print("persistence strategy: " + persistenceStrategy + "\n");
		pw.print("evictions: " + evictions + "\n");
		pw.print("latency: " + latency + "\n");
		pw.print("hits: " + hits + "\n");
		pw.print("misses: " + misses + "\n");
		pw.print("total: " + total + "\n");
		pw.print("hitratio: " + hitRatio + "%\n");
	}

	
	
	


	private static final Map<String,Collection<UsageSession>>  getSessionsByServer(){
		UsageSessionService uss = (UsageSessionService)ComponentManager.get("org.sakaiproject.event.api.UsageSessionService");
		if(uss == null) {
			throw new RuntimeException("Could not get UsageSessionService bean.");
		}
		// returns a Map<String,List<UsageSession>>, but the interface has the wrong return type
		Map<String,?> interfaceDefinitionIsWrong = uss.getOpenSessionsByServer();
		@SuppressWarnings("unchecked")
		Map<String,Collection<UsageSession>> sessionsByServer = (Map<String,Collection<UsageSession>>) interfaceDefinitionIsWrong;
		return sessionsByServer;
	}
	
	private static class WrappedException extends RuntimeException{
		public WrappedException(Exception e) {
			super(e);
		}
	}
}
