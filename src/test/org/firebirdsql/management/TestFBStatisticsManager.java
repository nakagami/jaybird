/*
 * $Id$
 *
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
package org.firebirdsql.management;

import java.io.OutputStream;
import java.io.ByteArrayOutputStream;

import java.sql.Connection;
import java.sql.Statement;
import java.sql.SQLException;

import org.firebirdsql.common.FBTestBase;
import org.firebirdsql.gds.impl.GDSType;

import static org.firebirdsql.common.FBTestProperties.*;

/**
 * Test the FBStatisticsManager class
 */
public class TestFBStatisticsManager extends FBTestBase {

//    private FBManager fbManager;

    private FBStatisticsManager statManager;

    private OutputStream loggingStream;

    public static final String DEFAULT_TABLE = ""
        + "CREATE TABLE TEST ("
        + "     TESTVAL INTEGER NOT NULL"
        + ")";

    public TestFBStatisticsManager(String name) throws ClassNotFoundException {
        super(name);
        Class.forName("org.firebirdsql.jdbc.FBDriver");
    }

    public void setUp() throws Exception {
        super.setUp();

        loggingStream = new ByteArrayOutputStream();
    
        statManager = new FBStatisticsManager(getGdsType());
        if (getGdsType() == GDSType.getType("PURE_JAVA") || getGdsType() == GDSType.getType("NATIVE")) {
            statManager.setHost(DB_SERVER_URL);
            statManager.setPort(DB_SERVER_PORT);
        }
        statManager.setUser(DB_USER);
        statManager.setPassword(DB_PASSWORD);
        statManager.setDatabase(getDatabasePath());
        statManager.setLogger(loggingStream);
    }

    private void createTestTable() throws SQLException {
        createTestTable(DEFAULT_TABLE);
    }

    private void createTestTable(String tableDef) throws SQLException {
        Connection conn = getConnectionViaDriverManager();
        try {
            Statement stmt = conn.createStatement();
            stmt.executeUpdate(tableDef);
        } finally {
            conn.close();
        }
    }

    public void testGetHeaderPage() throws SQLException {
        statManager.getHeaderPage();
        String headerPage = loggingStream.toString();

        // Not a lot more we can really do to ensure that it's a real
        // header page, unfortunately :(
        assertTrue("The header page must include 'Database header page information'",
                headerPage.contains("Database header page information"));

        assertFalse("The statistics must not include data table info",
                headerPage.contains("Data pages"));
    }

    public void testGetDatabaseStatistics() throws SQLException {
        
        createTestTable();
        statManager.getDatabaseStatistics();
        String statistics = loggingStream.toString();

        assertTrue("The database page analysis must be in the statistics",
                statistics.contains("Data pages"));

        assertFalse("System table information must not be in basic statistics",
                statistics.contains("RDB$DATABASE"));
    }

    public void testGetStatsWithBadOptions() throws SQLException {
        try {
            statManager.getDatabaseStatistics(
                    (StatisticsManager.DATA_TABLE_STATISTICS
                     | StatisticsManager.SYSTEM_TABLE_STATISTICS
                     | StatisticsManager.INDEX_STATISTICS) * 2);
            fail("Options to getDatabaseStatistics must be a combination "
                    + "of DATA_TABLE_STATISTICS, SYSTEM_TABLE_STATISTICS "
                    + "and INDEX_STATISTICS, or 0");
        } catch (IllegalArgumentException e){
            // Ignore
        }
    }
    
    public void testGetSystemStats() throws SQLException {
    
        statManager.getDatabaseStatistics(
                StatisticsManager.SYSTEM_TABLE_STATISTICS);
        String statistics = loggingStream.toString();
        assertTrue("Statistics with SYSTEM_TABLE_STATISTICS option must "
                    + "include system table info",
                statistics.contains("RDB$DATABASE"));
    }

    public void testGetTableStatistics() throws SQLException {
        
        createTestTable();
        statManager.getTableStatistics(new String[]{"TEST"});
        String statistics = loggingStream.toString();

        System.out.println(statistics);
        
        assertTrue("The database page analysis must be in the statistics",
                statistics.contains("Data pages"));

        assertTrue("The table name must be in the statistics",
                statistics.contains("TEST"));
    }
}
