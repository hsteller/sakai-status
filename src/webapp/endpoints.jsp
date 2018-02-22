<%@page import="org.sakaiproject.status.StatusServlet"%>
<%@ page language="java" contentType="text/html; charset=UTF-8"
    pageEncoding="UTF-8"%>
<!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN" "http://www.w3.org/TR/html4/loose.dtd">
<html>
<head>
<meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
<title>Status Endpoints</title>
</head>
<body>
<h3>Endpoints</h3>
<ul>
<%	
	final String CTX = request.getContextPath();
	for (String endpoint:StatusServlet.endpoints){
		%>
			<li><a target="_blank" href="<%=CTX+endpoint%>"><%=endpoint%></a></li>		
		<%
	}	
	%>
	
	<li><a target="_blank" href="<%=CTX%>/sakai/tools/TOOL-ID">/sakai/tools/TOOL-ID</a></li>
	<li><a target="_blank" href="<%=CTX%>/sakai/caches/CACHE-NAME">/sakai/caches/CACHE-NAME</a></li>				

</ul>
</body>
</html>