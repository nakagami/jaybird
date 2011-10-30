/*
 * $Id$
 * 
 * Firebird Open Source J2EE Connector - JDBC Driver
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
 * can be obtained from a CVS history command.
 *
 * All rights reserved.
 */
package org.firebirdsql.jdbc;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.antlr.runtime.CharStream;
import org.antlr.runtime.CommonTokenStream;
import org.antlr.runtime.RecognitionException;
import org.firebirdsql.jdbc.parser.CaseInsensitiveStream;
import org.firebirdsql.jdbc.parser.JaybirdSqlLexer;
import org.firebirdsql.jdbc.parser.JaybirdSqlParser;
import org.firebirdsql.jdbc.parser.JaybirdStatementModel;

/**
 * Class to add the RETURNING clause to queries for returning generated keys.
 * 
 * @author <a href="mailto:mrotteveel@users.sourceforge.net">Mark Rotteveel</a>
 * @since 2.2
 */
public abstract class AbstractGeneratedKeysQuery {

    private static final int QUERY_TYPE_KEEP_UNMODIFIED = 1;
    private static final int QUERY_TYPE_ADD_ALL_COLUMNS = 2;
    private static final int QUERY_TYPE_ADD_INDEXED = 3;
    private static final int QUERY_TYPE_ADD_COLUMNS = 4;
    private static final int QUERY_TYPE_ALREADY_HAS_RETURNING = 5;

    private final String originalSQL;
    private String modifiedSQL;
    private int queryType = QUERY_TYPE_KEEP_UNMODIFIED;
    private int[] columnIndexes;
    private String[] columnNames;
    private boolean processed = false;
    private boolean generatesKeys = false;
    private JaybirdStatementModel statementModel;

    private AbstractGeneratedKeysQuery(String sql) {
        originalSQL = sql;
    }

    /**
     * Process SQL statement text according to autoGeneratedKeys value.
     * <p>
     * For Statement.NO_GENERATED_KEYS the statement will not be processed, for
     * Statement.RETURN_GENERATED_KEYS it will be processed.
     * </p>
     * <p>
     * The query will only be modified if 1) it is capable of returning keys (ie
     * INSERT, DELETE and UPDATE) and 2) does not already contain a RETURNING
     * clause.
     * </p>
     * 
     * @param sql
     *            SQL statement
     * @param autoGeneratedKeys
     *            Valid values {@link java.sql.Statement#NO_GENERATED_KEYS} and
     *            {@link java.sql.Statement#RETURN_GENERATED_KEYS}
     * @throws SQLException
     *             If the supplied autoGeneratedKeys value does not match valid
     *             values.
     */
    public AbstractGeneratedKeysQuery(String sql, int autoGeneratedKeys) throws SQLException {
        this(sql);
        if (autoGeneratedKeys == Statement.RETURN_GENERATED_KEYS) {
            queryType = QUERY_TYPE_ADD_ALL_COLUMNS;
        } else if (autoGeneratedKeys != Statement.NO_GENERATED_KEYS) {
            // TODO Add correct SQL State
            throw new FBSQLException("Supplied value for autoGeneratedKeys is invalid");
        }
    }

    /**
     * Process SQL statement for adding generated key columns by their ordinal
     * position.
     * <p>
     * The query will only be modified if 1) it is capable of returning keys (ie
     * INSERT, DELETE and UPDATE) and 2) does not already contain a RETURNING
     * clause.
     * </p>
     * <p>
     * The columns are added in ascending order of their index value, not by the
     * order of indexes in the columnIndexes array. The values of columnIndexes
     * are taken as the ORDINAL_POSITION returned by
     * {@link java.sql.DatabaseMetaData#getColumns(String, String, String, String)}
     * . When a column index does not exist for the table of the query, then it
     * will be discarded from the list silently.
     * </p>
     * 
     * @param sql
     *            SQL statement
     * @param columnIndexes
     *            Array of ORDINAL_POSITION values of the columns to return as
     *            generated key
     */
    public AbstractGeneratedKeysQuery(String sql, int[] columnIndexes) {
        this(sql);
        if (columnIndexes != null && columnIndexes.length != 0) {
            this.columnIndexes = columnIndexes.clone();
            queryType = QUERY_TYPE_ADD_INDEXED;
        }
    }

    /**
     * Process SQL statement for adding generated key columns by name.
     * <p>
     * The query will only be modified if 1) it is capable of returning keys (ie
     * INSERT, DELETE and UPDATE) and 2) does not already contain a RETURNING
     * clause.
     * </p>
     * <p>
     * The columnNames passed are taken as is and included in a new returning
     * clause. There is no check for actual existence of these columns, nor are
     * they quoted.
     * </p>
     * 
     * @param sql
     *            SQL statement
     * @param columnNames
     *            Array of column names to return as generated key
     */
    public AbstractGeneratedKeysQuery(String sql, String[] columnNames) {
        this(sql);
        if (columnNames != null && columnNames.length != 0) {
            this.columnNames = columnNames;
            queryType = QUERY_TYPE_ADD_COLUMNS;
        }
    }

    /**
     * Indicates if the query will generate keys.
     * 
     * @return <code>true</code> if the query will generate keys,
     *         <code>false</code> otherwise
     * @throws SQLException
     *             For errors accessing the metadata
     */
    public boolean generatesKeys() throws SQLException {
        process();
        return generatesKeys;
    }

    /**
     * Returns the actual query.
     * <p>
     * Use {@link #generatesKeys()} to see if this query will in fact generate
     * keys.
     * </p>
     * 
     * @return The SQL query
     * @throws SQLException
     *             For errors accessing the metadata
     */
    public String getQueryString() throws SQLException {
        process();
        return modifiedSQL;
    }

    /**
     * Parses the query and updates the query with generated keys if
     * modifications are needed or possible.
     * 
     * @throws SQLException
     *             For errors accessing the metadata
     */
    private void process() throws SQLException {
        if (processed) {
            return;
        }
        try {
            processStatementModel();
            updateQuery();
        } finally {
            processed = true;
        }
    }

    /**
     * Parses the original SQL query and checks if it already has a RETURNING
     * clause
     */
    private void processStatementModel() {
        try {
            statementModel = parseInsertStatement(originalSQL);
            if (statementModel.getReturningColumns().size() > 0) {
                queryType = QUERY_TYPE_ALREADY_HAS_RETURNING;
            }
        } catch (RecognitionException e) {
            // Unrecognized statement (so no INSERT, DELETE, UPDATE or UPDATE OR
            // INSERT statement), keep as is
            queryType = QUERY_TYPE_KEEP_UNMODIFIED;
        }
    }

    /**
     * Adds the generated key columns to the query.
     * 
     * @throws SQLException
     *             For errors accessing the metadata
     */
    private void updateQuery() throws SQLException {
        switch (queryType) {
        case QUERY_TYPE_ADD_ALL_COLUMNS:
            addAllColumns();
            break;
        case QUERY_TYPE_ADD_INDEXED:
            addIndexedColumns();
            break;
        case QUERY_TYPE_ADD_COLUMNS:
            addReturningClause();
            break;
        case QUERY_TYPE_ALREADY_HAS_RETURNING:
            generatesKeys = true;
            queryType = QUERY_TYPE_KEEP_UNMODIFIED;
            break;
        }
        // Not part of switch: elements of switch will modify queryType (eg when
        // nothing is added)
        if (queryType == QUERY_TYPE_KEEP_UNMODIFIED) {
            modifiedSQL = originalSQL;
        }
    }

    /**
     * Adds all available table columns to the query as generated keys.
     * 
     * @throws SQLException
     *             For errors accessing the metadata
     */
    private void addAllColumns() throws SQLException {
        DatabaseMetaData metaData = getDatabaseMetaData();
        ResultSet rs = metaData.getColumns(null, null, statementModel.getTableName(), null);
        List<String> columns = new ArrayList<String>();
        try {
            while (rs.next()) {
                columns.add(rs.getString(4));
            }
        } finally {
            rs.close();
        }
        columnNames = (String[]) columns.toArray(new String[0]);
        addReturningClause();
    }

    /**
     * Adds all columns referenced by columnIndexes to the query as generated
     * keys.
     * 
     * @throws SQLException
     *             For errors accessing the metadata
     */
    private void addIndexedColumns() throws SQLException {
        DatabaseMetaData metaData = getDatabaseMetaData();
        ResultSet rs = metaData.getColumns(null, null, statementModel.getTableName(), null);
        Arrays.sort(columnIndexes);
        List<String> columns = new ArrayList<String>();
        try {
            while (rs.next()) {
                if (Arrays.binarySearch(columnIndexes, rs.getInt(17)) >= 0) {
                    columns.add(rs.getString(4));
                }
            }
        } finally {
            rs.close();
        }
        columnNames = (String[]) columns.toArray(new String[0]);
        addReturningClause();
    }

    /**
     * Adds the columns in columnNames to the query as generated keys.
     */
    private void addReturningClause() {
        if (columnNames == null || columnNames.length == 0) {
            queryType = QUERY_TYPE_KEEP_UNMODIFIED;
            return;
        }
        generatesKeys = true;

        StringBuffer query = new StringBuffer(originalSQL);
        if (query.charAt(query.length() - 1) == ';') {
            query.deleteCharAt(query.length() - 1);
        }
        query.append("\n");
        query.append("RETURNING").append(" ");
        for (int i = 0; i < columnNames.length; i++) {
            query.append(columnNames[i]);

            if (i < columnNames.length - 1) {
                query.append(", ");
            }
        }

        modifiedSQL = query.toString();
    }

    /**
     * Returns the DatabaseMetaData object to be used when processing this
     * query. In general this should be a DatabaseMetaData object created from
     * the connection which will execute the query.
     * 
     * @return DatabaseMetaData object
     * @throws SQLException
     *             if a database access error occurs
     */
    abstract DatabaseMetaData getDatabaseMetaData() throws SQLException;

    /**
     * Parse the INSERT statement and extract the corresponding model.
     * 
     * @param sql
     *            SQL statement to parse.
     * 
     * @return instance of {@link JaybirdStatementModel}
     * 
     * @throws RecognitionException
     *             if statement cannot be parsed.
     */
    private JaybirdStatementModel parseInsertStatement(String sql) throws RecognitionException {
        CharStream stream = new CaseInsensitiveStream(sql);
        JaybirdSqlLexer lexer = new JaybirdSqlLexer(stream);
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);

        JaybirdSqlParser parser = new JaybirdSqlParser(tokenStream);
        parser.statement().getTree();

        JaybirdStatementModel statementModel = parser.getStatementModel();
        return statementModel;
    }
}