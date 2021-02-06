# State to reproduce
- MySQL Server Version - Server version: 5.7.29 MySQL Community Server (GPL) 
- Java Version - 1.8.0_275 
- OS - Mac OS X 10.15.7 

# Description of issue

Using EclipseLink and MySQL connector (8.0.23) with two queries, where the second query uses `.setFirstResult()` causes the following RuntimeException:
```
Internal Exception: java.sql.SQLException: Operation not allowed for a result set of type ResultSet.TYPE_FORWARD_ONLY.
Error Code: 0
```

## Proposed root-cause
This appears to be caused by the introduction of a [runtime ResultSetType check in MySQL Connector 8.0.20](https://dev.mysql.com/doc/relnotes/connector-j/8.0/en/news-8-0-20.html), combined with the cachePrepStmts setting causing the resultSetType value to be overridden.

1. The SQLException we get is caused by a runtime check against the [MySQL `ResultSet#absolute`](https://github.com/spullara/mysql-connector-java/blob/cc5922f6712c491d0cc46e846ae0dc674c9a5844/src/main/java/com/mysql/jdbc/ResultSetImpl.java#L591) method which is used by [Eclipselink's `DatabaseAccessor#basicExecuteCall`](https://github.com/eclipse-ee4j/eclipselink/blob/87e9d1437d64b6c7178d73d9ba7dc8e61448058f/foundation/org.eclipse.persistence.core/src/main/java/org/eclipse/persistence/internal/databaseaccess/DatabaseAccessor.java#L654). 
2. Eclipselink calls the absolute method against the resultSet when `FirstResult` is non-zero - this is a JPA pagination technique described here - https://stackoverflow.com/questions/16088949/how-to-paginate-a-jpa-query
3. However, we get the runtime exception because the resultSet is of type FORWARD_ONLY when it should be of type SCROLL_INSENSITIVE. The reason for that (in this case) is that the MySQL Connection has cached the preparedStatement for this query previously when it had a `firstResult` value of 0 and so was type FORWARD_ONLY. That cache resolution happens in [MySQL's `ConnectionImpl#clientPrepareStatement`](https://github.com/spullara/mysql-connector-java/blob/cc5922f6712c491d0cc46e846ae0dc674c9a5844/src/main/java/com/mysql/jdbc/ConnectionImpl.java#L4194)

```
	public synchronized java.sql.PreparedStatement prepareStatement(String sql,
			int resultSetType, int resultSetConcurrency) throws SQLException {
		checkClosed();

		//
		// FIXME: Create warnings if can't create results of the given
		// type or concurrency
		//
		PreparedStatement pStmt = null;
		
		boolean canServerPrepare = true;
		
		String nativeSql = getProcessEscapeCodesForPrepStmts() ? nativeSQL(sql): sql;
		
		if (this.useServerPreparedStmts && getEmulateUnsupportedPstmts()) {
			canServerPrepare = canHandleAsServerPreparedStatement(nativeSql);
		}
		
		if (this.useServerPreparedStmts && canServerPrepare) {
			if (this.getCachePreparedStatements()) {
				synchronized (this.serverSideStatementCache) {
					pStmt = (com.mysql.jdbc.ServerPreparedStatement)this.serverSideStatementCache.remove(sql);
					
					if (pStmt != null) {
						((com.mysql.jdbc.ServerPreparedStatement)pStmt).setClosed(false);
						pStmt.clearParameters();
					}
```

We've previously prepared a statement with the same sql, but a different resultSetType (because we're using `setFirstResult`, so we get the same statement, even though it has a different ResultSetType. In previous versions of MySQL connector, no error was thrown and we get the expected result. After 8.0.20, we get the runtime exception.

# Possible solution

1. Could we add the resultSetType to the serverSideStatementCache key? So that we're not overwriting the Statement's resultSetType?
2. Could we revert the runtime exception for resultSetType FORWARD_ONLY?

# Steps to reproduce issue
1. Set your jdbcURl, username, and password in persistence.xml.
2. Run the project: `mvn package exec:java`

As configured you should get the following exception:
```java
INFO]
[INFO] --- exec-maven-plugin:1.4.0:java (default-cli) @ mysql-bug-test ---
[EL Info]: 2021-02-05 16:39:37.713--ServerSession(1071113967)--EclipseLink, version: Eclipse Persistence Services - 2.7.8.v20201217-ecdf3c32c4
[EL Warning]: 2021-02-05 16:39:38.099--UnitOfWork(1164636779)--Exception [EclipseLink-4002] (Eclipse Persistence Services - 2.7.8.v20201217-ecdf3c32c4): org.eclipse.persistence.exceptions.DatabaseException
Internal Exception: java.sql.SQLException: Operation not allowed for a result set of type ResultSet.TYPE_FORWARD_ONLY.
Error Code: 0
Call: SELECT * FROM testdb.Cars
Query: DataReadQuery(sql="SELECT * FROM testdb.Cars")
[WARNING]
java.lang.reflect.InvocationTargetException
    at sun.reflect.NativeMethodAccessorImpl.invoke0 (Native Method)
    at sun.reflect.NativeMethodAccessorImpl.invoke (NativeMethodAccessorImpl.java:62)
    at sun.reflect.DelegatingMethodAccessorImpl.invoke (DelegatingMethodAccessorImpl.java:43)
    at java.lang.reflect.Method.invoke (Method.java:498)
    at org.codehaus.mojo.exec.ExecJavaMojo$1.run (ExecJavaMojo.java:293)
    at java.lang.Thread.run (Thread.java:748)
Caused by: javax.persistence.PersistenceException: Exception [EclipseLink-4002] (Eclipse Persistence Services - 2.7.8.v20201217-ecdf3c32c4): org.eclipse.persistence.exceptions.DatabaseException
Internal Exception: java.sql.SQLException: Operation not allowed for a result set of type ResultSet.TYPE_FORWARD_ONLY.
Error Code: 0
Call: SELECT * FROM testdb.Cars
Query: DataReadQuery(sql="SELECT * FROM testdb.Cars")
    at org.eclipse.persistence.internal.jpa.QueryImpl.getDetailedException (QueryImpl.java:391)
    at org.eclipse.persistence.internal.jpa.QueryImpl.executeReadQuery (QueryImpl.java:264)
    at org.eclipse.persistence.internal.jpa.QueryImpl.getResultList (QueryImpl.java:482)
    at com.maxwolffe.App.main (App.java:46)
    at sun.reflect.NativeMethodAccessorImpl.invoke0 (Native Method)
    at sun.reflect.NativeMethodAccessorImpl.invoke (NativeMethodAccessorImpl.java:62)
    at sun.reflect.DelegatingMethodAccessorImpl.invoke (DelegatingMethodAccessorImpl.java:43)
    at java.lang.reflect.Method.invoke (Method.java:498)
    at org.codehaus.mojo.exec.ExecJavaMojo$1.run (ExecJavaMojo.java:293)
    at java.lang.Thread.run (Thread.java:748)
Caused by: org.eclipse.persistence.exceptions.DatabaseException:
Internal Exception: java.sql.SQLException: Operation not allowed for a result set of type ResultSet.TYPE_FORWARD_ONLY.
Error Code: 0
Call: SELECT * FROM testdb.Cars
Query: DataReadQuery(sql="SELECT * FROM testdb.Cars")
```

If you change the value of `eclipselink.jdbc.property.cachePrepStmts` in App.java to `"false"` the error should go away and you'll see the following:

````
[INFO] --- exec-maven-plugin:1.4.0:java (default-cli) @ mysql-bug-test ---
[EL Info]: 2021-02-05 16:39:53.661--ServerSession(1814780595)--EclipseLink, version: Eclipse Persistence Services - 2.7.8.v20201217-ecdf3c32c4

Queries successful!
```

