package example

import org.springframework.transaction.TransactionDefinition

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReferenceArray
import grails.test.mixin.TestMixin
import grails.test.mixin.integration.IntegrationTestMixin
import org.junit.*
import static org.junit.Assert.*

@TestMixin(IntegrationTestMixin)
class ExampleTests {

    @grails.transaction.NotTransactional
    @Before
    void setUp() {
        User.withNewSession { session ->
            User.withTransaction([
                isolationLevel: TransactionDefinition.ISOLATION_READ_COMMITTED
            ]) {
                10.times { i ->
                    def user = new User(username: "testUser$i", password: "secret")
                    assertTrue user.validate()
                    assertFalse user.hasErrors()
                    assertNotNull(user.save(validate: false))
                }
            }
            session.flush()
        }
        assertEquals 10, User.withNewSession { User.count() }
    }

    @After
    void tearDown() {
        def users = User.list()
        users.each { u ->
            u.delete(flush: true)
        }
        assertEquals 0, User.withNewSession { User.count() }
    }

    //@Test
    @SuppressWarnings("GrMethodMayBeStatic")
    void everythingIsOkayOnSameThread() {
        final int SIZE = 2
        SIZE.times{ i ->
            def someUser = User.findByUsername("testUser$i")
            // creating new content in this session is all right
            def newUser = new User(username: "testUser${10 + i}", password: "letMeIn")
            assertTrue newUser.validate()
            assertNotNull newUser.save(validate: false, failOnError: true, flush: true)
            assertNotNull someUser
            assertEquals(11 + i, User.count())
        }
    }

    @Test
    void testConcurrentSubmissions() {
        assertEquals 10, User.count()
        final int SIZE = 2 * Runtime.getRuntime().availableProcessors() // or any value less than 10
        final CountDownLatch startLatch = new CountDownLatch(1)
        final CountDownLatch finishLatch = new CountDownLatch(SIZE)
        final AtomicReferenceArray<User> userRefs = new AtomicReferenceArray<>(SIZE)
        ExecutorService pool = Executors.newFixedThreadPool(SIZE)
        SIZE.times { i ->
            assertNotNull User.findByUsername("testUser$i")
            pool.submit(new Runnable() {
                @Override
                void run() {
                    startLatch.await()
                    String un = "testUser${10 + i}"
                    try {
                        User.withNewSession { session ->
                            User.withTransaction([
                                    name: "worker$i".toString(),
                                    isolationLevel:
                                            TransactionDefinition.ISOLATION_READ_COMMITTED
                            ]) {
                                try {
                                    // creating new content in this session is all right
                                    assertNull(User.findByUsername(un))
                                    def newUser = new User(username: un, password: "letMeIn")
                                    assertTrue(newUser.validate())
                                    assertNotNull(newUser.save(validate: false, failOnError:
                                            true, flush: true))
                                    assertFalse(newUser.hasErrors())
                                    assertEquals(0, newUser.errors.allErrors.size())
                                    userRefs.compareAndSet(i, null, newUser)

                                    //TODO getting something inserted in another session is problematic
                                    def someUser = User.findByUsername("testUser$i")
                                    //assertNotNull someUser
                                    println "$i -- $someUser -- ${User.getAll()}"
                                    assertEquals 11 + i, User.count()
                                } catch (Throwable e) {
                                    println "you got error $e"
                                    e.printStackTrace()
                                    println "end of your stack trace"
                                    //fail("you lose!")
                                }
                            }
                            session.flush()
                        }
                    } finally {
                        finishLatch.countDown()
                    }
                }
            })
        }
        // simulate throwing concurrent requests to another service
        startLatch.countDown()
        finishLatch.await()
        pool.shutdown()
        pool.awaitTermination(1, TimeUnit.SECONDS)
        def queuingJobs = pool.shutdownNow()
        assertEquals 0, queuingJobs.size()
        assertTrue pool.isTerminated()

        assertEquals SIZE + 10, User.withNewSession { User.count() }

        User.withNewSession { session ->
            User.withTransaction([
                    name: "mainThread-validation",
                    isolationLevel: TransactionDefinition.ISOLATION_READ_COMMITTED
            ]) {
                // see what we got
                10.times { i ->
                    User expected = User.findByUsername("testUser$i")
                    assertNotNull expected
                }

                SIZE.times { i ->
                    User actual = userRefs.get(i)
                    User expected = User.findByUsername("testUser${10 + i}")
                    assertNotNull actual
                    assertNotNull expected
                    assertEquals expected, actual
                }
            }
        }
    }
}
