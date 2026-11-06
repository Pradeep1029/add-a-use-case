package team.projectpulse.student;

import jakarta.persistence.EntityManager;
import org.json.JSONObject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.instructor.Instructor;
import team.projectpulse.notification.SubmittableItem;
import team.projectpulse.notification.SubmissionStatusService;
import team.projectpulse.system.EmailService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** UC-STU-deactivate-student action rows: real signed login JWTs through the actual filter chain. */
@Tag("UC-STU-deactivate-student")
class StudentDeactivationIntegrationTest extends AbstractIntegrationTest {
    private static final String API = "/api/v1";
    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired SubmissionStatusService submissionStatus;
    @MockitoSpyBean StudentRepository students;
    @MockitoSpyBean EmailService email;

    private String login(String email) throws Exception {
        var response = mvc.perform(post(API + "/users/login").with(httpBasic(email, "123456")))
                .andExpect(status().isOk()).andReturn().getResponse();
        return "Bearer " + new JSONObject(response.getContentAsString()).getJSONObject("data").getString("token");
    }
    private Instructor instructor(String email) {
        return em.createQuery("select i from Instructor i where i.email = :email", Instructor.class)
                .setParameter("email", email).getSingleResult();
    }
    private Map<String, List<Map<String, Object>>> snapshot(int id) {
        Map<String, List<Map<String, Object>>> snapshot = new LinkedHashMap<>();
        snapshot.put("user", jdbc.queryForList("select * from peer_evaluation_user where id = ?", id));
        snapshot.put("activities", jdbc.queryForList("select * from activity order by activity_id"));
        snapshot.put("evaluations", jdbc.queryForList("select * from peer_evaluation order by peer_evaluation_id"));
        snapshot.put("ratings", jdbc.queryForList("select * from rating order by rating_id"));
        return snapshot;
    }
    private void deactivate(String token, int id) throws Exception {
        mvc.perform(post(API + "/students/" + id + "/deactivation").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.flag").value(true))
                .andExpect(jsonPath("$.data.id").value(id)).andExpect(jsonPath("$.data.enabled").value(false));
    }

    @Test // T02: every persisted field except enabled and every work/rating row remain exact.
    void assignedInstructorDeactivatesWithoutChangingProfileMembershipOrWork() throws Exception {
        String token = login("b.gates@abc.edu");
        var before = snapshot(4);
        deactivate(token, 4);
        em.flush(); em.clear();
        var after = snapshot(4);
        assertThat(after.get("user").get(0).get("enabled")).isEqualTo(false);
        after.get("user").get(0).put("enabled", before.get("user").get(0).get("enabled"));
        assertThat(after).isEqualTo(before);
    }
    @Test // T03: owning admin, without course section assignment.
    void owningAdminCanReadThenDeactivateWithoutSectionAssignment() throws Exception {
        String token = login("b.wei@abc.edu");
        students.findById(4).orElseThrow().getSection().removeInstructor(instructor("b.wei@abc.edu"));
        em.flush();
        mvc.perform(get(API + "/students/4").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isOk());
        deactivate(token, 4);
    }
    @Test // T04-T06: outsiders, self, other student and anonymous cannot mutate.
    void unauthorizedRolesAndScopesCannotDeactivate() throws Exception {
        String bill = login("b.gates@abc.edu");
        students.findById(4).orElseThrow().getSection().removeInstructor(instructor("b.gates@abc.edu"));
        em.flush();
        var before = snapshot(4);
        for (String token : List.of(bill, login("t.cook@abc.edu"), login("j.smith@abc.edu"), login("e.hudson@abc.edu"))) {
            mvc.perform(post(API + "/students/4/deactivation").header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.flag").value(false));
        }
        mvc.perform(post(API + "/students/4/deactivation")).andExpect(status().isUnauthorized());
        assertThat(snapshot(4)).isEqualTo(before);
    }
    @Test // T07: details read cannot retain authority after assignment removal.
    void scopeLostAfterDetailsReadRefusesConfirmation() throws Exception {
        String token = login("b.gates@abc.edu");
        mvc.perform(get(API + "/students/4").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isOk());
        students.findById(4).orElseThrow().getSection().removeInstructor(instructor("b.gates@abc.edu"));
        em.flush();
        var before = snapshot(4);
        mvc.perform(post(API + "/students/4/deactivation").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isForbidden());
        assertThat(snapshot(4)).isEqualTo(before);
    }
    @Test // T09: uniform non-disclosing denials for unresolved target ids.
    void missingAndMalformedTargetsAreDeniedWithoutData() throws Exception {
        String token = login("b.wei@abc.edu");
        for (String id : List.of("999999", "not-a-number")) {
            mvc.perform(post(API + "/students/" + id + "/deactivation").header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("No permission."));
        }
    }
    @Test // T12: repeat needs no other active student and does not alter retained work.
    void repeatDeactivationSucceedsWhenNoOtherStudentIsActive() throws Exception {
        String token = login("b.gates@abc.edu");
        students.findAll().stream().filter(student -> student.getId() != 4).forEach(student -> student.setEnabled(false));
        em.flush(); deactivate(token, 4); em.flush();
        var beforeRepeat = snapshot(4);
        mvc.perform(post(API + "/students/4/deactivation").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.message").value("Student is already deactivated"));
        em.flush();
        assertThat(snapshot(4)).isEqualTo(beforeRepeat);
    }
    @Test // T21: an optional team is not a precondition; history stays.
    void teamlessStudentCanBeDeactivatedWithExistingHistoryRetained() throws Exception {
        String token = login("b.gates@abc.edu");
        students.findById(4).orElseThrow().setTeam(null); em.flush();
        var before = snapshot(4);
        deactivate(token, 4); em.flush(); em.clear();
        assertThat(students.findById(4).orElseThrow().getTeam()).isNull();
        var after = snapshot(4);
        after.get("user").get(0).put("enabled", before.get("user").get(0).get("enabled"));
        assertThat(after).isEqualTo(before);
    }
    @Test // T13: flush enabled=false to MySQL, fail inside real service transaction, then check fresh JDBC reads.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedPersistenceRollsBackStatusAndHistoryAndReturnsServerError() throws Exception {
        String token = login("b.gates@abc.edu");
        var before = snapshot(4);
        try {
            doAnswer(invocation -> {
                em.flush();
                throw new DataIntegrityViolationException("Forced persistence failure for rollback verification");
            }).when(students).save(argThat(student -> student != null && student.getId().equals(4)));
            mvc.perform(post(API + "/students/4/deactivation").header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.flag").value(false));
            assertThat(snapshot(4)).isEqualTo(before);
        } finally { reset(students); }
    }
    @Test // T17-T18: actual completed deactivation endpoint invalidates pre-existing student credentials.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void endpointCommitInvalidatesEarlierStudentTokenAndPassword() throws Exception {
        String studentToken = login("j.smith@abc.edu");
        String instructorToken = login("b.gates@abc.edu");
        mvc.perform(get(API + "/students/4").header(HttpHeaders.AUTHORIZATION, studentToken)).andExpect(status().isOk());
        var before = snapshot(4);
        try {
            deactivate(instructorToken, 4);
            mvc.perform(get(API + "/students/4").header(HttpHeaders.AUTHORIZATION, studentToken)).andExpect(status().isUnauthorized());
            for (String path : List.of("/activities", "/evaluations")) {
                mvc.perform(post(API + path).header(HttpHeaders.AUTHORIZATION, studentToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
            }
            mvc.perform(put(API + "/evaluations/1").header(HttpHeaders.AUTHORIZATION, studentToken)
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
            mvc.perform(post(API + "/users/login").with(httpBasic("j.smith@abc.edu", "123456"))).andExpect(status().isUnauthorized());
            assertThat(snapshot(4).get("activities")).isEqualTo(before.get("activities"));
            assertThat(snapshot(4).get("evaluations")).isEqualTo(before.get("evaluations"));
            assertThat(snapshot(4).get("ratings")).isEqualTo(before.get("ratings"));
        } finally {
            new TransactionTemplate(transactions).executeWithoutResult(ignored -> students.findById(4).orElseThrow().setEnabled(true));
        }
    }
    @Test // T22: removes both obligations/reminder recipients; active peers need not evaluate inactive teammate.
    void deactivationRemovesObligationsAndReminderRecipients() throws Exception {
        String token = login("b.gates@abc.edu");
        Student jerry = students.findById(6).orElseThrow();
        em.createQuery("delete from Rating r where r.peerEvaluation.evaluatee = :student and r.peerEvaluation.week = '2023-W32'")
                .setParameter("student", jerry).executeUpdate();
        em.createQuery("delete from PeerEvaluation e where e.evaluatee = :student and e.week = '2023-W32'")
                .setParameter("student", jerry).executeUpdate();
        em.flush();
        assertThat(submissionStatus.findOwing(2, SubmittableItem.WEEKLY_ACTIVITY_REPORT, "2023-W32")).extracting(Student::getId).contains(6);
        assertThat(submissionStatus.findOwing(2, SubmittableItem.PEER_EVALUATION, "2023-W32")).extracting(Student::getId).contains(4, 5, 6);
        deactivate(token, 6); em.flush();
        for (SubmittableItem item : SubmittableItem.values()) {
            assertThat(submissionStatus.findOwing(2, item, "2023-W32")).extracting(Student::getId).doesNotContain(6);
            mvc.perform(get(API + "/sections/2/submission-status").param("item", item.name()).param("week", "2023-W32")
                            .header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.teams[*].students[?(@.id == 6)]").isEmpty());
            doNothing().when(email).sendReminderEmail(anyString(), anyString(), anyString());
            mvc.perform(post(API + "/sections/2/reminders").header(HttpHeaders.AUTHORIZATION, token)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"item\":\"" + item.name() + "\",\"week\":\"2023-W32\"}"))
                    .andExpect(status().isOk());
        }
        assertThat(submissionStatus.findOwing(2, SubmittableItem.PEER_EVALUATION, "2023-W32")).extracting(Student::getId).doesNotContain(4, 5, 6);
        verify(email, never()).sendReminderEmail(eq("j.moon@abc.edu"), anyString(), anyString());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    // T20 / FR-SEC-active-account: coordinate real overlapping requests in both commit orders.
    void concurrentProfileAndDeactivationPreserveBothIntents(boolean pauseProfile) throws Exception {
        String token = login("b.gates@abc.edu");
        var before = snapshot(4);
        var paused = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor(task -> new Thread(task, "paused-student-update"));
        String profile = "{\"username\":\"j.smith@abc.edu\",\"firstName\":\"Concurrent profile\",\"lastName\":\"Smith\",\"email\":\"j.smith@abc.edu\",\"enabled\":true}";
        java.util.concurrent.Future<?> request = null;
        try {
            doAnswer(invocation -> {
                if (Thread.currentThread().getName().equals("paused-student-update")) {
                    paused.countDown();
                    if (!release.await(15, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for competing commit");
                }
                // Both services loaded a managed entity. save is redundant here; ORM dirty-checking,
                // flush, commit and all fresh database reads remain real. This spy only coordinates timing.
                return invocation.getArgument(0);
            }).when(students).save(argThat(student -> student != null && student.getId().equals(4)));
            request = executor.submit(() -> {
                try {
                    if (pauseProfile) {
                        mvc.perform(put(API + "/students/4").header(HttpHeaders.AUTHORIZATION, token)
                                .contentType(MediaType.APPLICATION_JSON).content(profile)).andExpect(status().isOk());
                    } else {
                        deactivate(token, 4);
                    }
                } catch (Exception exception) { throw new RuntimeException(exception); }
            });
            assertThat(paused.await(15, java.util.concurrent.TimeUnit.SECONDS)).as("first request reached save before its commit").isTrue();
            if (pauseProfile) deactivate(token, 4);
            else mvc.perform(put(API + "/students/4").header(HttpHeaders.AUTHORIZATION, token)
                    .contentType(MediaType.APPLICATION_JSON).content(profile)).andExpect(status().isOk());
            release.countDown();
            request.get(15, java.util.concurrent.TimeUnit.SECONDS);
            var after = snapshot(4);
            assertThat(after.get("user").get(0).get("enabled")).as("a profile write cannot restore access").isEqualTo(false);
            assertThat(after.get("user").get(0).get("first_name")).as("deactivation cannot overwrite a profile edit").isEqualTo("Concurrent profile");
            after.get("user").get(0).put("enabled", before.get("user").get(0).get("enabled"));
            after.get("user").get(0).put("first_name", before.get("user").get(0).get("first_name"));
            assertThat(after).isEqualTo(before);
        } finally {
            release.countDown();
            if (request != null) {
                try { request.get(20, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) { /* preserve primary failure */ }
            }
            executor.shutdownNow();
            reset(students);
            jdbc.update("update peer_evaluation_user set enabled = ?, first_name = ? where id = 4",
                    before.get("user").get(0).get("enabled"), before.get("user").get(0).get("first_name"));
        }
    }

}
