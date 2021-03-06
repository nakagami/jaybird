/*
 * Firebird Open Source JavaEE Connector - JDBC Driver
 *
 * Distributable under LGPL license.
 * You may obtain a copy of the License at http://www.gnu.org/copyleft/lgpl.html
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * LGPL License for more details.
 *
 * This file was created by members of the firebird development team.
 * All individual contributions remain the Copyright (C) of those
 * individuals.  Contributors to this file are either listed here or
 * can be obtained from a source control history command.
 *
 * All rights reserved.
 */
package org.firebirdsql.jdbc;

import org.firebirdsql.common.FBJUnit4TestBase;
import org.firebirdsql.gds.ISCConstants;
import org.firebirdsql.gds.JaybirdErrorCodes;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.sql.*;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Properties;
import java.util.TimeZone;

import static org.firebirdsql.common.FBTestProperties.*;
import static org.firebirdsql.common.JdbcResourceHelper.closeQuietly;
import static org.firebirdsql.common.matchers.SQLExceptionMatchers.*;
import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.*;

/**
 * Test suite for the FBDriver class implementation.
 *
 * @author <a href="mailto:rrokytskyy@users.sourceforge.net">Roman Rokytskyy</a>
 * @author <a href="mailto:mrotteveel@users.sourceforge.net">Mark Rotteveel</a>
 */
public class TestFBDriver extends FBJUnit4TestBase {

    private Connection connection;

    @Rule
    public final ExpectedException expectedException = ExpectedException.none();

    @After
    public void tearDown() throws Exception {
        closeQuietly(connection);
    }

    @Test
    public void testAcceptsURL() throws Exception {
        Driver driver = DriverManager.getDriver(getUrl());

        assertTrue(driver.acceptsURL(getUrl()));
    }

    @Test
    public void testConnect() throws Exception {
        Driver driver = DriverManager.getDriver(getUrl());
        connection = driver.connect(getUrl(), getDefaultPropertiesForConnection());

        assertNotNull("Connection is null", connection);
    }

    @Test
    public void testJdbcCompliant() throws Exception {
        Driver driver = DriverManager.getDriver(getUrl());

        // current driver is not JDBC compliant.
        assertTrue(driver.jdbcCompliant());
    }

    /**
     * This method tests if driver correctly handles warnings returned from
     * database. We use SQL dialect mismatch between client and server to
     * make server return us a warning.
     */
    @Test
    public void testWarnings() throws Exception {
        Properties info = (Properties) getDefaultPropertiesForConnection().clone();
        info.setProperty("set_db_sql_dialect", "1");

        // open connection and convert DB to SQL dialect 1
        connection = DriverManager.getConnection(getUrl(), info);
        SQLWarning warning = connection.getWarnings();

        assertNotNull("Connection should have at least one warning.", warning);
        assertThat(warning, allOf(
                isA(SQLWarning.class),
                errorCodeEquals(ISCConstants.isc_dialect_reset_warning),
                message(startsWith(getFbMessage(ISCConstants.isc_dialect_reset_warning)))
        ));

        connection.clearWarnings();

        assertNull("After clearing no warnings should be present.", connection.getWarnings());
    }

    @Test
    public void testDialect1() throws Exception {
        Properties info = (Properties) getDefaultPropertiesForConnection().clone();
        info.setProperty("isc_dpb_sql_dialect", "1");

        connection = DriverManager.getConnection(getUrl(), info);
        try (Statement stmt = connection.createStatement()) {
            // Dialect 1 allows double quotes in strings
            ResultSet rs = stmt.executeQuery("SELECT  cast(\"today\" as date) - 7 FROM rdb$database");

            assertTrue("Should have at least one row.", rs.next());
        }
    }

    @Test
    public void testGetSQLState() throws Exception {
        connection = getConnectionViaDriverManager();
        try (Statement stmt = connection.createStatement()) {
            expectedException.expect(SQLSyntaxErrorException.class);
            expectedException.expect(sqlState(is(FBSQLException.SQL_STATE_SYNTAX_ERROR)));

            stmt.executeQuery("select * from");
        }
    }

    @Test
    public void testLongRange() throws Exception {
        connection = getConnectionViaDriverManager();
        try (Statement s = connection.createStatement()) {
            s.execute("CREATE TABLE LONGTEST (LONGID DECIMAL(18) NOT NULL PRIMARY KEY)");
            s.execute("INSERT INTO LONGTEST (LONGID) VALUES (" + Long.MAX_VALUE + ")");
            try (ResultSet rs = s.executeQuery("SELECT LONGID FROM LONGTEST")) {
                assertTrue("Should have one row!", rs.next());
                assertEquals("Retrieved wrong value!", Long.MAX_VALUE, rs.getLong(1));
            }

            s.execute("DELETE FROM LONGTEST");
            s.execute("INSERT INTO LONGTEST (LONGID) VALUES (" + Long.MIN_VALUE + ")");
            try (ResultSet rs = s.executeQuery("SELECT LONGID FROM LONGTEST")) {
                assertTrue("Should have one row!", rs.next());
                assertEquals("Retrieved wrong value!", Long.MIN_VALUE, rs.getLong(1));
            }
        }
    }

    private static final TimeZone timeZoneUTC = TimeZone.getTimeZone("UTC");

    @Test
    public void testDate() throws Exception {
        connection = getConnectionViaDriverManager();
        try (Statement s = connection.createStatement()) {
            s.execute("CREATE TABLE DATETEST (DATEID INTEGER NOT NULL PRIMARY KEY, TESTDATE TIMESTAMP)");
            Calendar cal = new GregorianCalendar(timeZoneUTC);
            Timestamp x = Timestamp.valueOf("1917-02-17 20:59:31");

            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO DATETEST (DATEID, TESTDATE) VALUES (?,?)")) {
                ps.setInt(1, 1);
                ps.setTimestamp(2, x, cal);
                ps.execute();
            }

            try (ResultSet rs = s.executeQuery("SELECT TESTDATE FROM DATETEST WHERE DATEID=1")) {
                assertTrue("Should have one row!", rs.next());
                assertEquals("Retrieved wrong value!", x, rs.getTimestamp(1, cal));
            }
        }
    }

    /**
     * This test checks if transaction is rolled back when connection is closed,
     * but still has an active transaction associated with it.
     *
     * @throws Exception
     *         if something went wrong.
     */
    @Test
    public void testClose() throws Exception {
        connection = getConnectionViaDriverManager();
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("CREATE TABLE test(id INTEGER, test_value INTEGER)");
            stmt.executeUpdate("INSERT INTO test VALUES (1, 1)");
            connection.setAutoCommit(false);
            stmt.executeUpdate("UPDATE test SET test_value = 2 WHERE id = 1");
        } finally {
            connection.close();
        }

        connection = getConnectionViaDriverManager();
        try (Statement stmt = connection.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT test_value FROM test WHERE id = 1")) {
                assertTrue("Should have at least one row", rs.next());
                assertEquals("Value should be 1.", 1, rs.getInt(1));
                assertTrue("Should have only one row.", !rs.next());
            }
        } finally {
            connection.close();
        }
    }

    @Test
    public void testDummyPacketIntervalConnect() throws Exception {
        Properties props = getDefaultPropertiesForConnection();
        props.setProperty("soTimeout", "2000");

        Driver driver = DriverManager.getDriver(getUrl());
        connection = driver.connect(getUrl(), props);

        assertNotNull("Connection is null", connection);
    }

    /**
     * Connection url parsing itself is tested in {@link org.firebirdsql.gds.impl.TestDbAttachInfo}.
     */
    @Test
    public void testInvalidConnectionUrl() throws Exception {
        Properties props = getDefaultPropertiesForConnection();
        Driver driver = DriverManager.getDriver("jdbc:firebirdsql://localhost:c:/data/db/test.fdb");
        expectedException.expect(SQLNonTransientConnectionException.class);
        expectedException.expect(allOf(
                errorCodeEquals(JaybirdErrorCodes.jb_invalidConnectionString),
                message(containsString("Bad port: 'c:' is not a number"))));

        driver.connect("jdbc:firebirdsql://localhost:c:/data/db/test.fdb", props);
    }

}

