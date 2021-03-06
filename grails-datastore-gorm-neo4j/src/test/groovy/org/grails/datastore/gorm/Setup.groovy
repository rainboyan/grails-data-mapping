package org.grails.datastore.gorm

import grails.core.DefaultGrailsApplication
import grails.core.GrailsApplication
import grails.util.Holders
import org.apache.tomcat.jdbc.pool.DataSource
import org.apache.tomcat.jdbc.pool.PoolProperties
import org.grails.core.lifecycle.ShutdownOperations
import org.grails.datastore.gorm.events.AutoTimestampEventListener
import org.grails.datastore.gorm.events.DomainEventListener
import org.grails.datastore.gorm.neo4j.internal.tools.DumpGraphOnSessionFlushListener
import org.grails.datastore.gorm.neo4j.HashcodeEqualsAwareProxyFactory
import org.grails.datastore.gorm.neo4j.Neo4jDatastore
import org.grails.datastore.gorm.neo4j.Neo4jDatastoreTransactionManager
import org.grails.datastore.gorm.neo4j.Neo4jMappingContext
import org.grails.datastore.gorm.neo4j.TestServer
import org.grails.datastore.gorm.neo4j.rest.GrailsCypherRestGraphDatabase
import org.grails.datastore.mapping.core.Session
import org.grails.datastore.mapping.model.MappingContext
import org.grails.datastore.mapping.model.PersistentEntity
import org.grails.validation.GrailsDomainClassValidator
import org.neo4j.graphdb.GraphDatabaseService
import org.neo4j.server.web.WebServer
import org.neo4j.test.TestGraphDatabaseFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.support.GenericApplicationContext
import org.springframework.util.StringUtils
import org.springframework.validation.Errors
import org.springframework.validation.Validator

import java.lang.management.ManagementFactory
import java.lang.reflect.Field
import java.util.concurrent.TimeUnit

class Setup {

    protected static final Logger log = LoggerFactory.getLogger(getClass())

    static Neo4jDatastore datastore
    static GraphDatabaseService graphDb
    static WebServer webServer
    static skipIndexSetup = true
    static Closure extendedValidatorSetup = null

    static destroy() {
//        TxManager txManager = graphDb.getDependencyResolver().resolveDependency(TxManager)
//        log.info "before shutdown, active: $txManager.activeTxCount, committed $txManager.committedTxCount, started: $txManager.startedTxCount, rollback: $txManager.rolledbackTxCount, status: $txManager.status"
//        assert txManager.activeTxCount == 0, "something is wrong with connection handling - we still have $txManager.activeTxCount connections open"

        def enhancer = new GormEnhancer(datastore, new Neo4jDatastoreTransactionManager(datastore: datastore))
        enhancer.close()
        webServer?.stop()

//        graphDb.@neoDataSource.kernel.stop()


        graphDb?.shutdown()
        datastore.destroy()
        graphDb = null
        webServer = null
        datastore = null

        // force clearing of thread locals, Neo4j connection pool leaks :(
        Set<Thread> threadSet = Thread.getAllStackTraces().keySet()
        for(t in threadSet) {
            try {
                def f = Thread.getDeclaredField("threadLocals")
                f.accessible = true
                f.set(t, null)
            } catch (Throwable e) {
                println "ERROR: Cannot clear thread local $e.message"
            }
        }

        ShutdownOperations.runOperations()
        Holders.clear()
        log.info "after shutdown"
    }

    static Session setup(classes) {
        System.setProperty("neo4j.gorm.suite", "true")

        assert datastore == null
        def ctx = new GenericApplicationContext()
        ctx.refresh()

        boolean nativeId = Boolean.getBoolean("gorm.neo4j.test.nativeId")

        def nativeIdMapping = {
            id generator:'native'
        }
        MappingContext mappingContext = nativeId ? new Neo4jMappingContext(nativeIdMapping) : new Neo4jMappingContext()

        // setup datasource
        def testMode = System.getProperty("gorm_neo4j_test_mode", "embedded")


        switch (testMode) {
            case "embedded":
                graphDb = new TestGraphDatabaseFactory().newImpermanentDatabaseBuilder()
                        .setConfig("cache_type", "soft") // prevent hpc cache during tests, potentially leaking memory due to many restarts
                        .newGraphDatabase()
                break
            case "server":

                def impermanentDatabase = new TestGraphDatabaseFactory().newImpermanentDatabase()

                def port
                (port, webServer) = TestServer.startWebServer(impermanentDatabase)
                skipIndexSetup = true
                graphDb = new GrailsCypherRestGraphDatabase("http://localhost:$port/db/data") {
                    @Override
                    void shutdown() {
                        impermanentDatabase.shutdown()
                        super.shutdown()
                    }
                }
                break

            case "remote":
                break
            default:
                throw new IllegalStateException("dunno know how to handle mode $testMode")
        }
        datastore = new Neo4jDatastore(
                mappingContext,
                ctx,
                graphDb
        )
        datastore.skipIndexSetup = skipIndexSetup
        datastore.mappingContext.proxyFactory = new HashcodeEqualsAwareProxyFactory()


        for (Class cls in classes) {
            mappingContext.addPersistentEntity(cls)
        }

        def grailsApplication = new DefaultGrailsApplication(classes as Class[], Setup.getClassLoader())
        grailsApplication.mainContext = ctx
        grailsApplication.initialise()

        setupValidators(mappingContext, grailsApplication)


        def transactionManager = new Neo4jDatastoreTransactionManager(datastore: datastore)
        def enhancer = new GormEnhancer(datastore, transactionManager)
        enhancer.enhance()

        datastore.afterPropertiesSet()

        waitForIndexesBeingOnline(graphDb)

        mappingContext.addMappingContextListener({ e ->
            enhancer.enhance e
        } as MappingContext.Listener)


        ctx.addApplicationListener new DomainEventListener(datastore)
        ctx.addApplicationListener new AutoTimestampEventListener(datastore)

      // enable for debugging
        if (graphDb) {
            ctx.addApplicationListener new DumpGraphOnSessionFlushListener(graphDb)
        }
        def session = datastore.connect()
        session.beginTransaction()
        return session
    }

    static void setupValidators(MappingContext mappingContext, GrailsApplication grailsApplication) {

        setupValidator(mappingContext, grailsApplication, "TestEntity", [
                    supports: { Class c -> true },
                    validate: { o, Errors errors ->
                        if (!StringUtils.hasText(o.name)) {
                            errors.rejectValue("name", "name.is.blank")
                        }
                    }
                ] as Validator)

        setupValidator(mappingContext, grailsApplication, "Role")

        if (extendedValidatorSetup) {
            extendedValidatorSetup(mappingContext, grailsApplication)
        }
    }

    static void setupValidator(MappingContext mappingContext, GrailsApplication grailsApplication, String entityName, Validator validator = null) {
        PersistentEntity entity = mappingContext.persistentEntities.find { PersistentEntity e -> e.javaClass.simpleName == entityName }
        if (entity) {
            mappingContext.addEntityValidator(entity, validator ?:
                    new GrailsDomainClassValidator(
                            grailsApplication: grailsApplication,
                            domainClass: grailsApplication.getDomainClass(entity.javaClass.name)
                    ) )
        }
    }

    private static void waitForIndexesBeingOnline(GraphDatabaseService graphDb) {
        if (graphDb && (skipIndexSetup==false)) {
            def tx = graphDb.beginTx()
            try {
                graphDb.schema().awaitIndexesOnline(10, TimeUnit.SECONDS)
                tx.success()
            } finally {
                tx.close()
            }
        }
    }

}
