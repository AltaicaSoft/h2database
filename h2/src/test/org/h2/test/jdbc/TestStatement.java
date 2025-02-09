/*
 * Copyright 2004-2025 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashMap;

import org.h2.api.ErrorCode;
import org.h2.engine.SysProperties;
import org.h2.store.fs.FileUtils;
import org.h2.test.TestBase;
import org.h2.test.TestDb;

/**
 * Tests for the Statement implementation.
 */
public class TestStatement extends TestDb {

    private Connection conn;

    /**
     * Run just this test.
     *
     * @param a ignored
     */
    public static void main(String... a) throws Exception {
        TestBase.createCaller().init().testFromMain();
    }

    @Override
    public void test() throws Exception {
        deleteDb("statement");
        conn = getConnection("statement");
        testUnwrap();
        testUnsupportedOperations();
        testTraceError();
        testSavepoint();
        testConnectionRollback();
        testStatement();
        testPreparedStatement();
        testCloseOnCompletion();
        testIdentityMerge();
        testMultipleCommands();
        conn.close();
        deleteDb("statement");
        testIdentifiers();
        deleteDb("statement");
    }

    private void testUnwrap() throws SQLException {
        Statement stat = conn.createStatement();
        assertTrue(stat.isWrapperFor(Object.class));
        assertTrue(stat.isWrapperFor(Statement.class));
        assertTrue(stat.isWrapperFor(stat.getClass()));
        assertFalse(stat.isWrapperFor(Integer.class));
        assertTrue(stat == stat.unwrap(Object.class));
        assertTrue(stat == stat.unwrap(Statement.class));
        assertTrue(stat == stat.unwrap(stat.getClass()));
        assertThrows(ErrorCode.INVALID_VALUE_2, stat).
        unwrap(Integer.class);
    }

    private void testUnsupportedOperations() throws Exception {
        assertTrue(conn.getTypeMap().isEmpty());
        conn.setTypeMap(null);
        HashMap<String, Class<?>> map = new HashMap<>();
        conn.setTypeMap(map);
        map.put("x", Object.class);
        assertThrows(ErrorCode.FEATURE_NOT_SUPPORTED_1, conn).
            setTypeMap(map);
    }

    private void testTraceError() throws Exception {
        if (config.memory || config.networked || config.traceLevelFile != 0) {
            return;
        }
        Statement stat = conn.createStatement();
        String fileName = getBaseDir() + "/statement.trace.db";
        stat.execute("DROP TABLE TEST IF EXISTS");
        stat.execute("CREATE TABLE TEST(ID INT PRIMARY KEY)");
        stat.execute("INSERT INTO TEST VALUES(1)");
        try {
            stat.execute("ERROR");
        } catch (SQLException e) {
            // ignore
        }
        long lengthBefore = FileUtils.size(fileName);
        try {
            stat.execute("ERROR");
        } catch (SQLException e) {
            // ignore
        }
        long error = FileUtils.size(fileName);
        assertSmaller(lengthBefore, error);
        lengthBefore = error;
        try {
            stat.execute("INSERT INTO TEST VALUES(1)");
        } catch (SQLException e) {
            // ignore
        }
        error = FileUtils.size(fileName);
        assertEquals(lengthBefore, error);
        stat.execute("DROP TABLE TEST IF EXISTS");
    }

    private void testConnectionRollback() throws SQLException {
        Statement stat = conn.createStatement();
        conn.setAutoCommit(false);
        stat.execute("CREATE TABLE TEST(ID INT PRIMARY KEY, NAME VARCHAR(255))");
        stat.execute("INSERT INTO TEST VALUES(1, 'Hello')");
        conn.rollback();
        ResultSet rs = stat.executeQuery("SELECT * FROM TEST");
        assertFalse(rs.next());
        stat.execute("DROP TABLE TEST");
        conn.setAutoCommit(true);
    }

    private void testSavepoint() throws SQLException {
        Statement stat = conn.createStatement();
        stat.execute("CREATE TABLE TEST(ID INT PRIMARY KEY, NAME VARCHAR(255))");
        conn.setAutoCommit(false);
        stat.execute("INSERT INTO TEST VALUES(0, 'Hi')");
        Savepoint savepoint1 = conn.setSavepoint();
        int id1 = savepoint1.getSavepointId();
        assertThrows(ErrorCode.SAVEPOINT_IS_UNNAMED, savepoint1).
                getSavepointName();
        stat.execute("DELETE FROM TEST");
        conn.rollback(savepoint1);
        stat.execute("UPDATE TEST SET NAME='Hello'");
        Savepoint savepoint2a = conn.setSavepoint();
        Savepoint savepoint2 = conn.setSavepoint();
        conn.releaseSavepoint(savepoint2a);
        assertThrows(ErrorCode.SAVEPOINT_IS_INVALID_1, savepoint2a).
                getSavepointId();
        int id2 = savepoint2.getSavepointId();
        assertTrue(id1 != id2);
        stat.execute("UPDATE TEST SET NAME='Hallo' WHERE NAME='Hello'");
        Savepoint savepointTest = conn.setSavepoint("Joe's");
        assertTrue(savepointTest.toString().endsWith("name=Joe's"));
        stat.execute("DELETE FROM TEST");
        assertEquals(savepointTest.getSavepointName(), "Joe's");
        assertThrows(ErrorCode.SAVEPOINT_IS_NAMED, savepointTest).
                getSavepointId();
        conn.rollback(savepointTest);
        conn.commit();
        ResultSet rs = stat.executeQuery("SELECT NAME FROM TEST");
        rs.next();
        String name = rs.getString(1);
        assertEquals(name, "Hallo");
        assertFalse(rs.next());
        assertThrows(ErrorCode.SAVEPOINT_IS_INVALID_1, conn).
                rollback(savepoint2);
        stat.execute("DROP TABLE TEST");
        conn.setAutoCommit(true);
    }

    private void testStatement() throws SQLException {

        Statement stat = conn.createStatement();

        assertEquals(ResultSet.HOLD_CURSORS_OVER_COMMIT,
                conn.getHoldability());
        conn.setHoldability(ResultSet.CLOSE_CURSORS_AT_COMMIT);
        assertEquals(ResultSet.CLOSE_CURSORS_AT_COMMIT,
                conn.getHoldability());

        assertFalse(stat.isPoolable());
        stat.setPoolable(true);
        assertFalse(stat.isPoolable());

        // ignored
        stat.setCursorName("x");
        // fixed return value
        assertEquals(stat.getFetchDirection(), ResultSet.FETCH_FORWARD);
        // ignored
        stat.setFetchDirection(ResultSet.FETCH_REVERSE);
        // ignored
        stat.setMaxFieldSize(100);

        assertEquals(SysProperties.SERVER_RESULT_SET_FETCH_SIZE,
                stat.getFetchSize());
        stat.setFetchSize(10);
        assertEquals(10, stat.getFetchSize());
        stat.setFetchSize(0);
        assertEquals(SysProperties.SERVER_RESULT_SET_FETCH_SIZE,
                stat.getFetchSize());
        assertEquals(ResultSet.TYPE_FORWARD_ONLY,
                stat.getResultSetType());
        Statement stat2 = conn.createStatement(
                ResultSet.TYPE_SCROLL_SENSITIVE,
                ResultSet.CONCUR_READ_ONLY,
                ResultSet.HOLD_CURSORS_OVER_COMMIT);
        assertEquals(ResultSet.TYPE_SCROLL_SENSITIVE,
                stat2.getResultSetType());
        assertEquals(ResultSet.HOLD_CURSORS_OVER_COMMIT,
                stat2.getResultSetHoldability());
        assertEquals(ResultSet.CONCUR_READ_ONLY,
                stat2.getResultSetConcurrency());
        assertEquals(0, stat.getMaxFieldSize());
        assertFalse(stat2.isClosed());
        stat2.close();
        assertTrue(stat2.isClosed());


        ResultSet rs;
        int count;
        long largeCount;
        boolean result;

        stat.execute("CREATE TABLE TEST(ID INT)");
        stat.execute("SELECT * FROM TEST");
        stat.execute("DROP TABLE TEST");

        conn.getTypeMap();

        // this method should not throw an exception - if not supported, this
        // calls are ignored

        assertEquals(ResultSet.HOLD_CURSORS_OVER_COMMIT,
                stat.getResultSetHoldability());
        assertEquals(ResultSet.CONCUR_READ_ONLY,
                stat.getResultSetConcurrency());

        stat.cancel();
        stat.setQueryTimeout(10);
        assertTrue(stat.getQueryTimeout() == 10);
        stat.setQueryTimeout(0);
        assertTrue(stat.getQueryTimeout() == 0);
        assertThrows(ErrorCode.INVALID_VALUE_2, stat).setQueryTimeout(-1);
        assertTrue(stat.getQueryTimeout() == 0);
        trace("executeUpdate");
        count = stat.executeUpdate(
                "CREATE TABLE TEST(ID INT PRIMARY KEY,V VARCHAR(255))");
        assertEquals(0, count);
        count = stat.executeUpdate(
                "INSERT INTO TEST VALUES(1,'Hello')");
        assertEquals(1, count);
        count = stat.executeUpdate(
                "INSERT INTO TEST(V,ID) VALUES('JDBC',2)");
        assertEquals(1, count);
        count = stat.executeUpdate(
                "UPDATE TEST SET V='LDBC' WHERE ID=2 OR ID=1");
        assertEquals(2, count);
        count = stat.executeUpdate(
                "UPDATE TEST SET V='\\LDBC\\' WHERE V LIKE 'LDBC' ");
        assertEquals(2, count);
        count = stat.executeUpdate(
                "UPDATE TEST SET V='LDBC' WHERE V LIKE '\\\\LDBC\\\\'");
        trace("count:" + count);
        assertEquals(2, count);
        count = stat.executeUpdate("DELETE FROM TEST WHERE ID=-1");
        assertEquals(0, count);
        count = stat.executeUpdate("DELETE FROM TEST WHERE ID=2");
        assertEquals(1, count);
        largeCount = stat.executeLargeUpdate("DELETE FROM TEST WHERE ID=-1");
        assertEquals(0, largeCount);
        assertEquals(0, stat.getLargeUpdateCount());
        largeCount = stat.executeLargeUpdate("INSERT INTO TEST(V,ID) VALUES('JDBC',2)");
        assertEquals(1, largeCount);
        assertEquals(1, stat.getLargeUpdateCount());
        largeCount = stat.executeLargeUpdate("DELETE FROM TEST WHERE ID=2");
        assertEquals(1, largeCount);
        assertEquals(1, stat.getLargeUpdateCount());

        assertThrows(ErrorCode.METHOD_NOT_ALLOWED_FOR_QUERY, stat).
                executeUpdate("SELECT * FROM TEST");

        count = stat.executeUpdate("DROP TABLE TEST");
        assertTrue(count == 0);

        trace("execute");
        result = stat.execute(
                "CREATE TABLE TEST(ID INT PRIMARY KEY,V VARCHAR(255))");
        assertFalse(result);
        result = stat.execute("INSERT INTO TEST VALUES(1,'Hello')");
        assertFalse(result);
        result = stat.execute("INSERT INTO TEST(V,ID) VALUES('JDBC',2)");
        assertFalse(result);
        result = stat.execute("UPDATE TEST SET V='LDBC' WHERE ID=2");
        assertFalse(result);
        result = stat.execute("DELETE FROM TEST WHERE ID=3");
        assertFalse(result);
        result = stat.execute("SELECT * FROM TEST");
        assertTrue(result);
        result = stat.execute("DROP TABLE TEST");
        assertFalse(result);

        assertThrows(ErrorCode.METHOD_ONLY_ALLOWED_FOR_QUERY, stat).
                executeQuery("CREATE TABLE TEST(ID INT PRIMARY KEY,V VARCHAR(255))");

        stat.execute("CREATE TABLE TEST(ID INT PRIMARY KEY,V VARCHAR(255))");

        assertThrows(ErrorCode.METHOD_ONLY_ALLOWED_FOR_QUERY, stat).
                executeQuery("INSERT INTO TEST VALUES(1,'Hello')");

        assertThrows(ErrorCode.METHOD_ONLY_ALLOWED_FOR_QUERY, stat).
                executeQuery("UPDATE TEST SET V='LDBC' WHERE ID=2");

        assertThrows(ErrorCode.METHOD_ONLY_ALLOWED_FOR_QUERY, stat).
                executeQuery("DELETE FROM TEST WHERE ID=3");

        stat.executeQuery("SELECT * FROM TEST");

        assertThrows(ErrorCode.METHOD_ONLY_ALLOWED_FOR_QUERY, stat).
                executeQuery("DROP TABLE TEST");

        // getMoreResults
        rs = stat.executeQuery("SELECT * FROM TEST");
        assertFalse(stat.getMoreResults());
        assertThrows(ErrorCode.OBJECT_CLOSED, rs).next();
        assertTrue(stat.getUpdateCount() == -1);
        count = stat.executeUpdate("DELETE FROM TEST");
        assertFalse(stat.getMoreResults());
        assertTrue(stat.getUpdateCount() == -1);

        stat.execute("DROP TABLE TEST");
        stat.executeUpdate("DROP TABLE IF EXISTS TEST");

        assertNull(stat.getWarnings());
        stat.clearWarnings();
        assertNull(stat.getWarnings());
        assertTrue(conn == stat.getConnection());

        stat.close();
    }

    private void testCloseOnCompletion() throws SQLException {
        Statement stat = conn.createStatement();
        assertFalse(stat.isCloseOnCompletion());
        ResultSet rs = stat.executeQuery("VALUES 1");
        assertFalse(stat.isCloseOnCompletion());
        stat.closeOnCompletion();
        assertTrue(stat.isCloseOnCompletion());
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
        assertFalse(rs.next());
        rs.close();
        assertTrue(stat.isClosed());
        assertThrows(ErrorCode.OBJECT_CLOSED, stat).isCloseOnCompletion();
        assertThrows(ErrorCode.OBJECT_CLOSED, stat).closeOnCompletion();
        stat = conn.createStatement();
        stat.closeOnCompletion();
        rs = stat.executeQuery("VALUES 1");
        ResultSet rs2 = stat.executeQuery("VALUES 2");
        rs.close();
        assertFalse(stat.isClosed());
        rs2.close();
        assertTrue(stat.isClosed());
    }

    private void testIdentityMerge() throws SQLException {
        Statement stat = conn.createStatement();
        stat.execute("drop table if exists test1");
        stat.execute("create table test1(id identity, x int)");
        stat.execute("drop table if exists test2");
        stat.execute("create table test2(id identity, x int)");
        stat.execute("merge into test1(x) key(x) values(5)",
                Statement.RETURN_GENERATED_KEYS);
        ResultSet keys;
        keys = stat.getGeneratedKeys();
        keys.next();
        assertEquals(1, keys.getInt(1));
        stat.execute("insert into test2(x) values(10), (11), (12)");
        stat.execute("merge into test1(x) key(x) values(5)",
                Statement.RETURN_GENERATED_KEYS);
        keys = stat.getGeneratedKeys();
        keys.next();
        assertEquals(1, keys.getInt(1));
        assertFalse(keys.next());
        stat.execute("merge into test1(x) key(x) values(6)",
                Statement.RETURN_GENERATED_KEYS);
        keys = stat.getGeneratedKeys();
        keys.next();
        assertEquals(2, keys.getInt(1));
        stat.execute("drop table test1, test2");
    }

    private void testPreparedStatement() throws SQLException{
        Statement stat = conn.createStatement();
        stat.execute("create table test(id int primary key, name varchar(255))");
        stat.execute("insert into test values(1, 'Hello')");
        stat.execute("insert into test values(2, 'World')");
        PreparedStatement ps = conn.prepareStatement(
                "select name from test where id in (select id from test where name REGEXP ?)");
        ps.setString(1, "Hello");
        ResultSet rs = ps.executeQuery();
        assertTrue(rs.next());
        assertEquals("Hello", rs.getString("name"));
        assertFalse(rs.next());
        ps.setString(1, "World");
        rs = ps.executeQuery();
        assertTrue(rs.next());
        assertEquals("World", rs.getString("name"));
        assertFalse(rs.next());
        //Changes the table structure
        stat.execute("create index t_id on test(name)");
        //Test the prepared statement again to check if the internal cache attributes were reset
        ps.setString(1, "Hello");
        rs = ps.executeQuery();
        assertTrue(rs.next());
        assertEquals("Hello", rs.getString("name"));
        assertFalse(rs.next());
        ps.setString(1, "World");
        rs = ps.executeQuery();
        assertTrue(rs.next());
        assertEquals("World", rs.getString("name"));
        assertFalse(rs.next());
        ps = conn.prepareStatement("insert into test values(?, ?)");
        ps.setInt(1, 3);
        ps.setString(2, "v3");
        ps.addBatch();
        ps.setInt(1, 4);
        ps.setString(2, "v4");
        ps.addBatch();
        assertTrue(Arrays.equals(new int[] {1, 1}, ps.executeBatch()));
        ps.setInt(1, 5);
        ps.setString(2, "v5");
        ps.addBatch();
        ps.setInt(1, 6);
        ps.setString(2, "v6");
        ps.addBatch();
        assertTrue(Arrays.equals(new long[] {1, 1}, ps.executeLargeBatch()));
        ps.setInt(1, 7);
        ps.setString(2, "v7");
        assertEquals(1, ps.executeUpdate());
        assertEquals(1, ps.getUpdateCount());
        ps.setInt(1, 8);
        ps.setString(2, "v8");
        assertEquals(1, ps.executeLargeUpdate());
        assertEquals(1, ps.getLargeUpdateCount());
        stat.execute("drop table test");
    }

    private void testMultipleCommands() throws SQLException{
        Statement stat = conn.createStatement();
        stat.executeQuery("VALUES 1; VALUES 2");
        stat.close();
    }

    private void testIdentifiers() throws SQLException {
        Connection conn = getConnection("statement");

        Statement stat = conn.createStatement();
        assertEquals("SOME_ID", stat.enquoteIdentifier("SOME_ID", false));
        assertEquals("\"SOME ID\"", stat.enquoteIdentifier("SOME ID", false));
        assertEquals("\"SOME_ID\"", stat.enquoteIdentifier("SOME_ID", true));
        assertEquals("\"FROM\"", stat.enquoteIdentifier("FROM", false));
        assertEquals("\"Test\"", stat.enquoteIdentifier("Test", false));
        assertEquals("\"test\"", stat.enquoteIdentifier("test", false));
        assertEquals("\"TOP\"", stat.enquoteIdentifier("TOP", false));
        assertEquals("\"Test\"", stat.enquoteIdentifier("\"Test\"", false));
        assertEquals("\"Test\"", stat.enquoteIdentifier("\"Test\"", true));
        assertEquals("\"\"\"Test\"", stat.enquoteIdentifier("\"\"\"Test\"", true));
        assertEquals("\"\"", stat.enquoteIdentifier("", false));
        assertEquals("\"\"", stat.enquoteIdentifier("", true));
        assertEquals("U&\"\"", stat.enquoteIdentifier("U&\"\"", false));
        assertEquals("U&\"\"", stat.enquoteIdentifier("U&\"\"", true));
        assertEquals("U&\"\0100\"", stat.enquoteIdentifier("U&\"\0100\"", false));
        assertEquals("U&\"\0100\"", stat.enquoteIdentifier("U&\"\0100\"", true));
        assertThrows(NullPointerException.class, () -> stat.enquoteIdentifier(null, false));
        assertThrows(ErrorCode.INVALID_NAME_1, () -> stat.enquoteIdentifier("\"Test", true));
        assertThrows(ErrorCode.INVALID_NAME_1, () -> stat.enquoteIdentifier("\"a\"a\"", true));
        assertThrows(ErrorCode.INVALID_NAME_1, () -> stat.enquoteIdentifier("U&\"a\"a\"", true));
        assertThrows(ErrorCode.STRING_FORMAT_ERROR_1, () -> stat.enquoteIdentifier("U&\"\\111\"", true));
        assertEquals("U&\"\\02b0\"", stat.enquoteIdentifier("\u02B0", false));

        assertTrue(stat.isSimpleIdentifier("SOME_ID_1"));
        assertFalse(stat.isSimpleIdentifier("SOME ID"));
        assertFalse(stat.isSimpleIdentifier("FROM"));
        assertFalse(stat.isSimpleIdentifier("Test"));
        assertFalse(stat.isSimpleIdentifier("test"));
        assertFalse(stat.isSimpleIdentifier("TOP"));
        assertFalse(stat.isSimpleIdentifier("_"));
        assertFalse(stat.isSimpleIdentifier("_1"));
        assertFalse(stat.isSimpleIdentifier("\u02B0"));

        conn.close();
        deleteDb("statement");
        conn = getConnection("statement;DATABASE_TO_LOWER=TRUE");

        Statement stat2 = conn.createStatement();
        assertEquals("some_id", stat2.enquoteIdentifier("some_id", false));
        assertEquals("\"some id\"", stat2.enquoteIdentifier("some id", false));
        assertEquals("\"some_id\"", stat2.enquoteIdentifier("some_id", true));
        assertEquals("\"from\"", stat2.enquoteIdentifier("from", false));
        assertEquals("\"Test\"", stat2.enquoteIdentifier("Test", false));
        assertEquals("\"TEST\"", stat2.enquoteIdentifier("TEST", false));
        assertEquals("\"top\"", stat2.enquoteIdentifier("top", false));

        assertTrue(stat2.isSimpleIdentifier("some_id"));
        assertFalse(stat2.isSimpleIdentifier("some id"));
        assertFalse(stat2.isSimpleIdentifier("from"));
        assertFalse(stat2.isSimpleIdentifier("Test"));
        assertFalse(stat2.isSimpleIdentifier("TEST"));
        assertFalse(stat2.isSimpleIdentifier("top"));

        conn.close();
        deleteDb("statement");
        conn = getConnection("statement;DATABASE_TO_UPPER=FALSE");

        Statement stat3 = conn.createStatement();
        assertEquals("SOME_ID", stat3.enquoteIdentifier("SOME_ID", false));
        assertEquals("some_id", stat3.enquoteIdentifier("some_id", false));
        assertEquals("\"SOME ID\"", stat3.enquoteIdentifier("SOME ID", false));
        assertEquals("\"some id\"", stat3.enquoteIdentifier("some id", false));
        assertEquals("\"SOME_ID\"", stat3.enquoteIdentifier("SOME_ID", true));
        assertEquals("\"some_id\"", stat3.enquoteIdentifier("some_id", true));
        assertEquals("\"FROM\"", stat3.enquoteIdentifier("FROM", false));
        assertEquals("\"from\"", stat3.enquoteIdentifier("from", false));
        assertEquals("Test", stat3.enquoteIdentifier("Test", false));
        assertEquals("\"TOP\"", stat3.enquoteIdentifier("TOP", false));
        assertEquals("\"top\"", stat3.enquoteIdentifier("top", false));

        assertTrue(stat3.isSimpleIdentifier("SOME_ID"));
        assertTrue(stat3.isSimpleIdentifier("some_id"));
        assertFalse(stat3.isSimpleIdentifier("SOME ID"));
        assertFalse(stat3.isSimpleIdentifier("some id"));
        assertFalse(stat3.isSimpleIdentifier("FROM"));
        assertFalse(stat3.isSimpleIdentifier("from"));
        assertTrue(stat3.isSimpleIdentifier("Test"));
        assertFalse(stat3.isSimpleIdentifier("TOP"));
        assertFalse(stat3.isSimpleIdentifier("top"));
        assertThrows(NullPointerException.class, () -> stat3.isSimpleIdentifier(null));

        conn.close();
    }

}
