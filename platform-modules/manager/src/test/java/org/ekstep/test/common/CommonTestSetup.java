package org.ekstep.test.common;

import java.io.File;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;
import org.cassandraunit.utils.EmbeddedCassandraServerHelper;
import org.ekstep.cassandra.connector.util.CassandraConnector;
import org.ekstep.common.Platform;
import org.ekstep.common.dto.Response;
import org.ekstep.graph.engine.common.TestParams;
import org.ekstep.graph.engine.router.ActorBootstrap;
import org.ekstep.graph.engine.router.GraphEngineActorPoolMgr;
import org.ekstep.graph.service.util.DriverUtil;
import org.ekstep.taxonomy.mgr.impl.TaxonomyManagerImpl;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;

import com.datastax.driver.core.Session;

import akka.actor.ActorRef;
import akka.util.Timeout;
import scala.concurrent.duration.Duration;

/**
 * @author gauraw
 *
 */
public class CommonTestSetup {

	static ClassLoader classLoader = CommonTestSetup.class.getClassLoader();
	static File definitionLocation = new File(classLoader.getResource("definitions/").getFile());
	private static TaxonomyManagerImpl taxonomyMgr = new TaxonomyManagerImpl();
	private static GraphDatabaseService graphDb = null;

	private static String NEO4J_SERVER_ADDRESS = "localhost:7687";
	private static String GRAPH_DIRECTORY_PROPERTY_KEY = "graph.dir";
	private static String BOLT_ENABLED = "true";

	public static ActorRef reqRouter = null;

	public static Session session = null;

	protected static long timeout = 50000;
	protected static Timeout t = new Timeout(Duration.create(30, TimeUnit.SECONDS));

	@AfterClass
	public static void afterTest() throws Exception {
		System.out.println("CommonTestSetup:::::::::::::: - After");
		DriverUtil.closeDrivers();
		tearEmbeddedNeo4JSetup();
		tearEmbeddedCassandraSetup();
	}

	@BeforeClass
	public static void before() throws Exception {
		System.out.println("CommonTestSetup:::::::::::::: - Before");
		ActorBootstrap.getActorSystem();
		reqRouter = GraphEngineActorPoolMgr.getRequestRouter();
		tearEmbeddedNeo4JSetup();
		setupEmbeddedNeo4J();
		setupEmbeddedCassandra();
	}

	private static void registerShutdownHook(final GraphDatabaseService graphDb) {
		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				graphDb.shutdown();
			}
		});
	}

	private static void setupEmbeddedNeo4J() throws Exception {
		GraphDatabaseSettings.BoltConnector bolt = GraphDatabaseSettings.boltConnector("0");
		graphDb = new GraphDatabaseFactory()
				.newEmbeddedDatabaseBuilder(new File(Platform.config.getString(GRAPH_DIRECTORY_PROPERTY_KEY)))
				.setConfig(bolt.type, TestParams.BOLT.name()).setConfig(bolt.enabled, BOLT_ENABLED)
				.setConfig(bolt.address, NEO4J_SERVER_ADDRESS).newGraphDatabase();
		registerShutdownHook(graphDb);
	}

	protected static void setupEmbeddedCassandra() {

		try {
			EmbeddedCassandraServerHelper.startEmbeddedCassandra("/cassandra-unit.yaml", 100000L);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	protected static void executeScript(String... querys) {

		try {
			session = CassandraConnector.getSession();
			for (String query : querys) {
				session.execute(query);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private static void tearEmbeddedCassandraSetup() {
		try {
			if (!session.isClosed())
				session.close();
			EmbeddedCassandraServerHelper.cleanEmbeddedCassandra();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private static void tearEmbeddedNeo4JSetup() throws Exception {
		if (null != graphDb)
			graphDb.shutdown();
		Thread.sleep(2000);
		deleteEmbeddedNeo4j(new File(Platform.config.getString(GRAPH_DIRECTORY_PROPERTY_KEY)));
	}

	private static void deleteEmbeddedNeo4j(final File emDb) {
		if (emDb.exists()) {
			if (emDb.isDirectory()) {
				for (File child : emDb.listFiles()) {
					deleteEmbeddedNeo4j(child);
				}
			}
			try {
				emDb.delete();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	protected static void loadDefinition(String... paths) throws Exception {
		if (null != paths && paths.length > 0) {
			for (String path : paths) {
				File file = new File(classLoader.getResource(path).getFile());
				String definition = FileUtils.readFileToString(file);
				createDefinition("domain", definition);
			}
		}
	}

	private static void createDefinition(String graphId, String definition) throws Exception {
		Response resp = taxonomyMgr.updateDefinition(graphId, definition);
		if (!resp.getParams().getStatus().equalsIgnoreCase(TestParams.successful.name())) {
			System.out.println(resp.getParams().getErr() + " :: " + resp.getParams().getErrmsg());
		}
	}
}
