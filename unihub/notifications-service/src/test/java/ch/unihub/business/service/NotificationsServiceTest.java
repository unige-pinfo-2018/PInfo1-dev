package ch.unihub.business.service;

import javax.inject.Inject;
import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;

import ch.unihub.dom.Notification;
import com.google.gson.Gson;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.jboss.shrinkwrap.resolver.api.maven.Maven;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

@RunWith(Arquillian.class)
public class NotificationsServiceTest {

	private static final Gson gson = new Gson();
	private HashMap<String, Session> sessions = new HashMap<>();
	private static Session kikiSession;
	private static Session arthurSession;
	private final static String recipient = "arthur";
	private final static String initiator = "kiki";
	private static List<Notification> receivedNotifications = new ArrayList<>();
	private static Notification hotNotification;
	// If the user was denied at websocket connection
	private static boolean beenDenied = false;

	private final static Logger logger = LoggerFactory.getLogger(NotificationsServiceTest.class);

	private static Session createMockSession(final String username) throws IOException {
		Session mockedSession = Mockito.mock(Session.class);
		RemoteEndpoint.Basic mockedBasic = Mockito.mock(RemoteEndpoint.Basic.class);
		// Saves sent notifications
		Mockito.doAnswer(
				(Answer<Void>) invocationOnMock -> {
					Object arg = invocationOnMock.getArguments()[0];
					if ((arg instanceof String) && arg.equals(NotificationsService.UNAUTHORIZED)) {
						beenDenied = true;
						return null;
					}
					try {
						// Multiple notifications: probably a GET request
						Notification[] notifications = gson.fromJson((String) arg, Notification[].class);
						Collections.addAll(receivedNotifications, notifications);
					} catch (Exception ignored) {
						// One notification, probably just created and sent to the recipient
						hotNotification = gson.fromJson((String) arg, Notification.class);
					}
					return null;
				}
		).when(mockedBasic).sendText(Mockito.anyString());
		Mockito.when(mockedSession.getBasicRemote()).thenReturn(mockedBasic);
		Mockito.when(mockedSession.getId()).thenReturn(username);
		return mockedSession;
	}

	static {
		try {
			kikiSession = createMockSession(initiator);
			arthurSession = createMockSession(recipient);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Inject
	private NotificationsService notificationsService;

	@Deployment
	public static WebArchive createDeployment() {
		File[] libs = Maven.resolver()
				.loadPomFromFile("pom.xml").importCompileAndRuntimeDependencies().resolve()
				.withTransitivity().as(File.class);
		File[] gsonLib = Maven.resolver().loadPomFromFile("pom.xml")
				.importTestDependencies().resolve().withTransitivity().asFile();
		return ShrinkWrap.create(WebArchive.class, "unihub-integration-test-notifications.war")
				.addPackages(true, "ch.unihub")
				.addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml")
				.setWebXML(new File("src/test/resources", "/web.xml"))
				.addAsWebInfResource(new File("src/main/webapp/WEB-INF/ejb-jar.xml"), "ejb-jar.xml")
				.addAsWebInfResource(new File("src/main/webapp/WEB-INF/jboss-web.xml"), "jboss-web.xml")
				.addAsWebInfResource(new File("src/main/webapp/WEB-INF/shiro.ini"), "shiro.ini")
				.addAsResource("test-persistence.xml", "META-INF/persistence.xml")
				.addAsLibraries(libs)
				.addAsLibraries(gsonLib);
	}

	@Before
	public void before() throws IOException {
		// Registers users in the notifications websocket service
		NotificationsService.usernamesWithSessionIds.put(recipient, recipient);
		NotificationsService.usernamesWithSessionIds.put(initiator, initiator);
		// Opens connections
		notificationsService.onOpen(arthurSession, recipient, recipient);
		// Creates 6 notifications for same user
		notificationsService.onOpen(kikiSession, initiator, initiator);
		for (int i = 0; i < 6; i++) {
			final String content = "This is the body of notification " + (i + 1);
			NotificationServiceMessage.CreateBody body = new NotificationServiceMessage.CreateBody(
					recipient,
					content
			);
			NotificationServiceMessage notification = new NotificationServiceMessage(
					NotificationServiceMessage.MessageType.CREATE,
					gson.toJson(body)
			);
			notificationsService.onMessage(kikiSession, gson.toJson(notification));
		}
	}

	@After
	public void after() throws IOException {
		receivedNotifications.clear();
		hotNotification = null;
		String deleteAll = gson.toJson(new NotificationServiceMessage(
				NotificationServiceMessage.MessageType.DELETE,
				"all"
		));
		notificationsService.onMessage(arthurSession, deleteAll);
		notificationsService.onMessage(kikiSession, deleteAll);
		notificationsService.onClose(kikiSession);
		notificationsService.onClose(arthurSession);
		beenDenied = false;
	}

	@Test
	public void t00_getLastNotificationsShouldReturn5Notifications() throws IOException {
		NotificationServiceMessage message = new NotificationServiceMessage(
				NotificationServiceMessage.MessageType.GET,
				"last"
		);
		notificationsService.onMessage(
				arthurSession,
				gson.toJson(message)
		);
		Assert.assertFalse(receivedNotifications.isEmpty());
		Assert.assertEquals(5, receivedNotifications.size());
		final Notification firstNotification = receivedNotifications.get(0);
		Assert.assertEquals("arthur", firstNotification.getUsername());
		Assert.assertEquals("This is the body of notification 1", firstNotification.getContent());
		Assert.assertNotNull(firstNotification.getDateCreated());
	}

	@Test
	public void t01_setAllReadShouldSetAllNotificationsToRead() throws IOException {
		NotificationServiceMessage message = new NotificationServiceMessage(
				NotificationServiceMessage.MessageType.UPDATE,
				"read"
		);
		notificationsService.onMessage(
				arthurSession,
				gson.toJson(message)
		);
		message = new NotificationServiceMessage(
				NotificationServiceMessage.MessageType.GET,
				"all"
		);
		notificationsService.onMessage(
				arthurSession,
				gson.toJson(message)
		);
		receivedNotifications.forEach(notification -> Assert.assertTrue(notification.isRead()));
	}

	@Test
	public void t02_canCreateAndReceiveNotification() throws IOException {
		final String notifRecipient = "kiki";
		final String notifBody = "hello there";
		NotificationServiceMessage message = new NotificationServiceMessage(
				NotificationServiceMessage.MessageType.CREATE,
				gson.toJson(new NotificationServiceMessage.CreateBody(
						notifRecipient,
						notifBody
				))
		);
		notificationsService.onMessage(arthurSession, gson.toJson(message));
		Assert.assertNotNull(hotNotification);
		Assert.assertEquals(notifRecipient, hotNotification.getUsername());
		Assert.assertEquals(notifBody, hotNotification.getContent());
		Assert.assertFalse(hotNotification.isRead());
	}

	@Test
	public void t03_shouldReturnAllNotifications() throws IOException {
		NotificationServiceMessage message = new NotificationServiceMessage(
				NotificationServiceMessage.MessageType.GET,
				"all"
		);
		notificationsService.onMessage(arthurSession, gson.toJson(message));
		Assert.assertNotNull(receivedNotifications);
		Assert.assertEquals(6, receivedNotifications.size());
	}

	@Test
	public void t04_shouldReturnRangeOfNotifications() throws IOException {
		NotificationServiceMessage message = new NotificationServiceMessage(
				NotificationServiceMessage.MessageType.GET,
				"range 1-4"
		);
		notificationsService.onMessage(arthurSession, gson.toJson(message));
		Assert.assertNotNull(receivedNotifications);
		Assert.assertEquals(4, receivedNotifications.size());
	}

	@Test
	public void t05_shouldDeleteAllNotificationsFromGivenUser() throws IOException {
		NotificationServiceMessage message = new NotificationServiceMessage(
				NotificationServiceMessage.MessageType.DELETE,
				"all"
		);
		notificationsService.onMessage(arthurSession, gson.toJson(message));
		notificationsService.onMessage(arthurSession, gson.toJson(new NotificationServiceMessage(
				NotificationServiceMessage.MessageType.GET,
				"all"
		)));
		Assert.assertEquals(0, receivedNotifications.size());
	}

	@Test
	public void t06_shouldNotAuthorizeWrongWebsocketSessionId() throws IOException {
		Assert.assertFalse(beenDenied);
		notificationsService.onOpen(arthurSession, recipient, "");
		Assert.assertTrue(beenDenied);
	}

	@Test
	public void t07_shouldNotAuthorizeWrongUsername() throws IOException {
		Assert.assertFalse(beenDenied);
		notificationsService.onOpen(arthurSession, recipient + "_wrong", recipient);
		Assert.assertTrue(beenDenied);
	}
}
