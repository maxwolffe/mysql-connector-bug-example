# State to reproduce
- MySQL Server Version - Server version: 5.7.29 MySQL Community Server (GPL) 
- Java Version - 1.8.0_275 
- OS - Mac OS X 10.15.7 
- EclipseLink version - 2.7.8 (latest 2.x.x)
- MySQL connector version - 8.0.23 (latest)
- Relevant jdbc connector settings - `eclipselink.jdbc.property.cachePrepStmts=true`

# Description of issue

## What I'm doing
Running the same sql query `SELECT * FROM testdb.Cars` twice:
1. Once as is, via eclipselink entity manager. 
2. The second time with `setFirstResult` set to index 2 - which should return results starting from index 2. 

## What I expect to happen
The behaviour from MySQL Connector 8.0.19. 

First query returns results 0-10
Second query returns results 2-10

## What is happening
The second query uses `.setFirstResult()` causes the following RuntimeException:
```
Internal Exception: java.sql.SQLException: Operation not allowed for a result set of type ResultSet.TYPE_FORWARD_ONLY.
Error Code: 0
```
This is new as of mysql-connector-java 8.0.20.

# Work arounds
1. Use msyql-connector-j 8.0.19
2. Set `eclipselink.jdbc.property.cachePrepStmts` to `false` - this prevents the caching and returns the correct resultSetType.

## Proposed root-cause
This appears to be caused by the introduction of a [runtime ResultSetType check in MySQL Connector 8.0.20](https://dev.mysql.com/doc/relnotes/connector-j/8.0/en/news-8-0-20.html), combined with the cachePrepStmts setting causing the resultSetType value to be overridden.

1. The SQLException we get is caused by a runtime check against the [MySQL `ResultSet#absolute`](https://github.com/mysql/mysql-connector-j/blob/18bbd5e68195d0da083cbd5bd0d05d76320df7cd/src/main/user-impl/java/com/mysql/cj/jdbc/result/ResultSetImpl.java#L368) method which is used by [Eclipselink's `DatabaseAccessor#basicExecuteCall`](https://github.com/eclipse-ee4j/eclipselink/blob/87e9d1437d64b6c7178d73d9ba7dc8e61448058f/foundation/org.eclipse.persistence.core/src/main/java/org/eclipse/persistence/internal/databaseaccess/DatabaseAccessor.java#L654). 
2. Eclipselink calls the absolute method against the resultSet when `FirstResult` is non-zero - this is a JPA pagination technique described here - https://stackoverflow.com/questions/16088949/how-to-paginate-a-jpa-query
3. However, we get the runtime exception because the resultSet is of type FORWARD_ONLY when it should be of type SCROLL_INSENSITIVE. The reason for that (in this case) is that the MySQL Connection has cached the preparedStatement for this query previously when it had a `firstResult` value of 0 and so was type FORWARD_ONLY. That cache resolution happens in [MySQL's `ConnectionImpl#clientPrepareStatement`](https://github.com/mysql/mysql-connector-j/blob/d64b664fa93e81296a377de031b8123a67e6def2/src/main/user-impl/java/com/mysql/cj/jdbc/ConnectionImpl.java#L1616)

Last line in the below snippet
```
    @Override
    public java.sql.PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        synchronized (getConnectionMutex()) {
            checkClosed();

            //
            // FIXME: Create warnings if can't create results of the given type or concurrency
            //
            ClientPreparedStatement pStmt = null;

            boolean canServerPrepare = true;

            String nativeSql = this.processEscapeCodesForPrepStmts.getValue() ? nativeSQL(sql) : sql;

            if (this.useServerPrepStmts.getValue() && this.emulateUnsupportedPstmts.getValue()) {
                canServerPrepare = canHandleAsServerPreparedStatement(nativeSql);
            }

            if (this.useServerPrepStmts.getValue() && canServerPrepare) {
                if (this.cachePrepStmts.getValue()) {
                    synchronized (this.serverSideStatementCache) {
                        pStmt = this.serverSideStatementCache.remove(new CompoundCacheKey(this.database, sql));

```

We've previously prepared a statement with the same sql, but a different resultSetType (because we're using `setFirstResult`), so we get the same statement, even though it has a different ResultSetType. In previous versions of MySQL connector, no error was thrown and we get the expected result. After 8.0.20, we get the runtime exception.

# Possible solution

1. Could we add the resultSetType to the serverSideStatementCache compound cache key? So that we're not overwriting the second query's resultSetType?

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

