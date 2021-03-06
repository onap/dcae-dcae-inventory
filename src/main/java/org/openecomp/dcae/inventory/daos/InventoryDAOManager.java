/*-
 * ============LICENSE_START=======================================================
 * dcae-inventory
 * ================================================================================
 * Copyright (C) 2017 AT&T Intellectual Property. All rights reserved.
 * ================================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ============LICENSE_END=========================================================
 */

package org.openecomp.dcae.inventory.daos;

import org.openecomp.dcae.inventory.InventoryConfiguration;
import org.openecomp.dcae.inventory.dbthings.StringListArgument;
import io.dropwizard.jdbi.DBIFactory;
import io.dropwizard.setup.Environment;
import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.util.BooleanMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

/**
 * Reluctantly made this into a singleton in order to have access to the DAOs in the request handling code. Didn't
 * want to change the interface on the handlers because they are generated by Swagger and I wanted to flexibility
 * to swap in changes easily. This meant sacrificing dependency injection which is preferred.
 *
 * Created by mhwang on 4/19/16.
 */
public final class InventoryDAOManager {

    private static InventoryDAOManager instance;

    public static InventoryDAOManager getInstance() {
        if (instance == null) {
            instance = new InventoryDAOManager();
        }

        return instance;
    }

    public static class InventoryDAOManagerSetupException extends RuntimeException {

        public InventoryDAOManagerSetupException(String message) {
            super(message);
        }

    }

    private final static Logger LOG = LoggerFactory.getLogger(InventoryDAOManager.class);
    // WATCH! Table creation order matters where mapping tables refer to other tables for foreign keys.
    private final static List<Class> DAO_CLASSES = Arrays.asList(DCAEServiceTypesDAO.class, DCAEServicesDAO.class,
            DCAEServiceComponentsDAO.class, DCAEServicesComponentsMapsDAO.class);

    private DBI jdbi;
    private Environment environment;
    private InventoryConfiguration configuration;

    private InventoryDAOManager() {
    }

    /**
     * Setup the manager
     *
     * Saving the Dropwizard environment and configuration which are used to construct the DBI object in a later
     * initialize call. This method can only be called once to be safe and to avoid runtime problems that could be
     * caused if the global instance of this class gets into a weird state (Couldn't use Java's `final` qualifier).
     *
     * @param environment
     * @param inventoryConfiguration
     */
    public void setup(Environment environment, InventoryConfiguration inventoryConfiguration) {
        if (this.environment == null && this.configuration == null) {
            this.environment = environment;
            this.configuration = inventoryConfiguration;
        } else {
            throw new InventoryDAOManagerSetupException("InventoryDAOManager setup can only be called once.");
        }
    }

    /**
     * Initialize the manager
     *
     * Create the underlying validated DBI object that is used to manage database connections
     */
    public void initialize() {
        final DBIFactory factory = new DBIFactory();
        final DBI jdbi = factory.build(this.environment, this.configuration.getDataSourceFactory(), "dcae-database");
        jdbi.registerArgumentFactory(new StringListArgument());

        for (Class<? extends InventoryDAO> daoClass : DAO_CLASSES) {
            final InventoryDAO dao = jdbi.onDemand(daoClass);

            if (dao.checkIfTableExists()) {
                LOG.info(String.format("Sql table exists: %s", daoClass.getSimpleName()));
            } else {
                dao.createTable();
                LOG.info(String.format("Sql table created: %s", daoClass.getSimpleName()));
            }
        }

        // CREATE VIEWS
        // TODO: This doesn't belong here and is not consistent with the above approach. Make it better.
        try (Handle jdbiHandle = jdbi.open()) {
            String viewName = "dcae_service_types_latest";
            String checkQuery = String.format("select exists (select * from information_schema.tables where table_name = '%s')",
                    viewName);

            if (jdbiHandle.createQuery(checkQuery).map(BooleanMapper.FIRST).first()) {
                LOG.info(String.format("Sql view exists: %s", viewName));
            } else {
                StringBuilder sb = new StringBuilder(String.format("create view %s as ", viewName));
                sb.append("select s.* from dcae_service_types s ");
                sb.append("join (select type_name, max(type_version) as max_version from dcae_service_types group by type_name) as f ");
                sb.append("on s.type_name = f.type_name and s.type_version = f.max_version");

                jdbiHandle.execute(sb.toString());
                LOG.info(String.format("Sql view created: %s", viewName));
            }
        } catch (Exception e) {
            throw new RuntimeException("", e);
        }

        // Do this assignment at the end after performing table checks to ensure that connection is good
        this.jdbi = jdbi;
    }

    private InventoryDAO getDAO(Class<? extends InventoryDAO> klass) {
        if (jdbi == null) {
            throw new RuntimeException("InventoryDAOManager has not been initialized!");
        }

        // Using this approach to constructing the DAO, the client is not responsible for closing the handle.
        // http://jdbi.org/sql_object_overview/
        // > In this case we do not need to (and in fact shouldn’t) ever take action to close the handle the sql object uses.
        return jdbi.onDemand(klass);
    }

    public DCAEServicesDAO getDCAEServicesDAO() {
        return (DCAEServicesDAO) this.getDAO(DCAEServicesDAO.class);
    }

    public DCAEServiceComponentsDAO getDCAEServiceComponentsDAO() {
        return (DCAEServiceComponentsDAO) this.getDAO(DCAEServiceComponentsDAO.class);
    }

    public DCAEServicesComponentsMapsDAO getDCAEServicesComponentsDAO() {
        return (DCAEServicesComponentsMapsDAO) this.getDAO(DCAEServicesComponentsMapsDAO.class);
    }

    public DCAEServiceTransactionDAO getDCAEServiceTransactionDAO() {
        return jdbi.onDemand(DCAEServiceTransactionDAO.class);
    }

    public DCAEServiceTypesDAO getDCAEServiceTypesDAO() {
        return (DCAEServiceTypesDAO) this.getDAO(DCAEServiceTypesDAO.class);
    }

    /**
     * Must close the handle that is returned here. It is AutoCloseable so just use it as a try-with-resource.
     *
     * @return
     */
    public Handle getHandle() {
        return this.jdbi.open();
    }

}
