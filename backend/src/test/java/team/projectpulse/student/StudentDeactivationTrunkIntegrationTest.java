package team.projectpulse.student;

import jakarta.persistence.EntityManager;
import org.json.JSONObject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.evaluation.*;
import team.projectpulse.instructor.Instructor;
import team.projectpulse.user.PeerEvaluationUser;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Shared policies only: deactivation is a fixture change until the dedicated action is built. */
@Tag("UC-STU-deactivate-student")
class StudentDeactivationTrunkIntegrationTest extends AbstractIntegrationTest {
    private static final String API = "/api/v1";
    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired JsonMapper json;
    @Autowired JwtEncoder encoder;
    @Autowired PlatformTransactionManager transactions;
    @Autowired PeerEvaluationRepository evaluations;

    private String login(String email) throws Exception {
        var response = mvc.perform(post(API + "/users/login").with(httpBasic(email, "123456")))
                .andExpect(status().isOk()).andReturn().getResponse();
        return "Bearer " + new JSONObject(response.getContentAsString()).getJSONObject("data").getString("token");
    }

    private Student student(String email) {
        return em.createQuery("select s from Student s where s.email = :email", Student.class)
                .setParameter("email", email).getSingleResult();
    }

    private Instructor instructor(String email) {
        return em.createQuery("select i from Instructor i where i.email = :email", Instructor.class)
                .setParameter("email", email).getSingleResult();
    }

    private void committedStatus(String email, boolean enabled) {
        new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
            var user = em.createQuery("select u from PeerEvaluationUser u where u.email = :email", PeerEvaluationUser.class)
                    .setParameter("email", email).getSingleResult();
            user.setEnabled(enabled);
        }); // Returns only after the transaction commits; no outer test transaction in its callers.
    }

    @Test // T17, T18: real login token reused after a separate committed fixture deactivation.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void preDeactivationStudentTokenCannotReadOrWriteAndDisabledPasswordCannotLogin() throws Exception {
        String token = login("j.smith@abc.edu");
        long before = evaluations.count();
        mvc.perform(get(API + "/students/4").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isOk());
        try {
            committedStatus("j.smith@abc.edu", false);
            mvc.perform(get(API + "/students/4").header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.flag").value(false));
            for (String path : List.of("/activities", "/evaluations")) {
                mvc.perform(post(API + path).header(HttpHeaders.AUTHORIZATION, token)
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andExpect(status().isUnauthorized());
            }
            mvc.perform(put(API + "/evaluations/1").header(HttpHeaders.AUTHORIZATION, token)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post(API + "/users/login").with(httpBasic("j.smith@abc.edu", "123456")))
                    .andExpect(status().isUnauthorized());
            assertThat(evaluations.count()).isEqualTo(before);
            login("e.hudson@abc.edu");
            login("b.wei@abc.edu");
        } finally {
            committedStatus("j.smith@abc.edu", true);
        }
    }

    @Test // T19: FR applies to instructors too; stale signed instructor credentials are refused.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void preDeactivationInstructorTokenIsRefusedAfterCommit() throws Exception {
        String token = login("b.gates@abc.edu");
        try {
            committedStatus("b.gates@abc.edu", false);
            mvc.perform(get(API + "/students/4").header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post(API + "/users/login").with(httpBasic("b.gates@abc.edu", "123456")))
                    .andExpect(status().isUnauthorized());
        } finally {
            committedStatus("b.gates@abc.edu", true);
        }
    }

    private String signedToken(Object userId) {
        Instant now = Instant.now();
        var builder = JwtClaimsSet.builder().issuer("self").subject("test@abc.edu")
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("authorities", "ROLE_instructor");
        if (userId != null) builder.claim("userId", userId);
        return "Bearer " + encoder.encode(JwtEncoderParameters.from(builder.build())).getTokenValue();
    }

    @Test // T19: actual decoder + converter + entry point, rather than Spring's mock JWT shortcut.
    void signedMissingMalformedAndDeletedIdentitiesReceive401() throws Exception {
        for (Object id : java.util.Arrays.asList(null, "bad", 1.5, 2147483648L, -1, 999999)) {
            mvc.perform(get(API + "/students/4").header(HttpHeaders.AUTHORIZATION, signedToken(id)))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.flag").value(false));
        }
        Student temporary = new Student("temporary@abc.edu", "Temp", "Account", "temporary@abc.edu", "unused", true, "student");
        temporary.setSection(student("j.smith@abc.edu").getSection());
        em.persist(temporary);
        em.flush();
        String deletedUserToken = signedToken(temporary.getId());
        em.remove(temporary);
        em.flush();
        mvc.perform(get(API + "/students/4").header(HttpHeaders.AUTHORIZATION, deletedUserToken))
                .andExpect(status().isUnauthorized());
    }

    @Test // T03-T05, T07, T09, T30: shared existing detail/private-comment routes only.
    void owningAdminAndAssignedInstructorHaveScopeButOutsidersAndStudentsDoNot() throws Exception {
        Student target = student("e.hudson@abc.edu");
        int id = target.getId();
        String adminToken = login("b.wei@abc.edu");
        String assignedToken = login("b.gates@abc.edu");
        String outsiderToken = login("t.cook@abc.edu");
        String selfToken = login("e.hudson@abc.edu");
        target.getSection().removeInstructor(instructor("b.wei@abc.edu"));
        em.flush();
        mvc.perform(get(API + "/students/" + id).header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk());
        String details = API + "/evaluations/students/" + id + "/week/2023-W31/details";
        for (String token : List.of(adminToken, assignedToken)) {
            mvc.perform(get(details).header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].privateComment").isNotEmpty());
        }
        for (String token : List.of(outsiderToken, selfToken)) {
            mvc.perform(get(details).header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isForbidden());
        }
        target.getSection().removeInstructor(instructor("b.gates@abc.edu"));
        em.flush();
        mvc.perform(get(details).header(HttpHeaders.AUTHORIZATION, assignedToken)).andExpect(status().isForbidden());
        mvc.perform(get(API + "/students/999999").header(HttpHeaders.AUTHORIZATION, adminToken)).andExpect(status().isForbidden());
        mvc.perform(get(API + "/students/not-an-id").header(HttpHeaders.AUTHORIZATION, adminToken)).andExpect(status().isForbidden());
    }

    @Test // T20: both forged deactivation and stale reactivation through existing profile PUT.
    void profilePutPreservesPersistedStatusInBothDirections() throws Exception {
        String admin = login("b.wei@abc.edu");
        Student target = student("e.hudson@abc.edu");
        int id = target.getId();
        for (boolean stored : List.of(true, false)) {
            target.setEnabled(stored);
            em.flush();
            String body = json.writeValueAsString(Map.of("username", target.getUsername(), "firstName", "Updated",
                    "lastName", target.getLastName(), "email", target.getEmail(), "enabled", !stored));
            mvc.perform(put(API + "/students/" + id).header(HttpHeaders.AUTHORIZATION, admin)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(stored))
                    .andExpect(jsonPath("$.data.firstName").value("Updated"));
            em.flush();
            em.clear();
            target = em.find(Student.class, id);
            assertThat(target.isEnabled()).isEqualTo(stored);
        }
    }

    private String evaluationBody(Student evaluator, Student evaluatee, String week) throws Exception {
        List<Map<String, Object>> ratings = evaluator.getSection().getRubric().getCriteria().stream()
                .map(c -> Map.<String, Object>of("criterionId", c.getCriterionId(), "actualScore", 1.0)).toList();
        return json.writeValueAsString(Map.of("week", week, "evaluatorId", evaluator.getId(),
                "evaluateeId", evaluatee.getId(), "ratings", ratings, "publicComment", "changed public", "privateComment", "changed private"));
    }

    @Test // T23: a genuinely new tuple, same team/open week; no duplicate can mask the refusal.
    void inactiveEvaluateeCreateWithoutExistingTupleIsRefused() throws Exception {
        String token = login("j.smith@abc.edu");
        Student john = student("j.smith@abc.edu");
        Student eric = student("e.hudson@abc.edu");
        evaluations.delete(evaluations.findByWeekAndEvaluatorIdAndEvaluateeId("2023-W32", john.getId(), eric.getId()).orElseThrow());
        em.flush();
        assertThat(evaluations.findByWeekAndEvaluatorIdAndEvaluateeId("2023-W32", john.getId(), eric.getId())).isEmpty();
        assertThat(john.getTeam().getTeamId()).isEqualTo(eric.getTeam().getTeamId());
        eric.setEnabled(false);
        em.flush();
        long count = evaluations.count();
        mvc.perform(post(API + "/evaluations").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content(evaluationBody(john, eric, "2023-W32")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("inactive student Eric Hudson")));
        em.clear();
        assertThat(evaluations.count()).isEqualTo(count);
        assertThat(evaluations.findByWeekAndEvaluatorIdAndEvaluateeId("2023-W32", john.getId(), eric.getId())).isEmpty();
    }

    @Test // T24: stored open-week evaluation, exact persisted ratings/comments unchanged on refusal.
    void inactiveEvaluateeEditPreservesPersistedEvaluation() throws Exception {
        String token = login("j.smith@abc.edu");
        Student john = student("j.smith@abc.edu");
        Student eric = student("e.hudson@abc.edu");
        PeerEvaluation stored = evaluations.findByWeekAndEvaluatorIdAndEvaluateeId("2023-W32", john.getId(), eric.getId()).orElseThrow();
        eric.setEnabled(false);
        String publicComment = stored.getPublicComment();
        String privateComment = stored.getPrivateComment();
        double total = stored.getTotalScore();
        List<Double> scores = stored.getRatings().stream().map(Rating::getActualScore).toList();
        int evaluationId = stored.getPeerEvaluationId();
        em.flush();
        long count = evaluations.count();
        mvc.perform(put(API + "/evaluations/" + evaluationId).header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content(evaluationBody(john, eric, "2023-W32")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("inactive student Eric Hudson")));
        em.clear();
        PeerEvaluation after = evaluations.findById(evaluationId).orElseThrow();
        assertThat(evaluations.count()).isEqualTo(count);
        assertThat(after.getPublicComment()).isEqualTo(publicComment);
        assertThat(after.getPrivateComment()).isEqualTo(privateComment);
        assertThat(after.getTotalScore()).isEqualTo(total);
        assertThat(after.getRatings()).extracting(Rating::getActualScore).containsExactlyElementsOf(scores);
    }

    @Test // T28: a real zero is still a received record; scope is the recipient's course section/week.
    void zeroScoreInactiveRecipientIsRetainedAndEmptyInactiveTeammateIsOmitted() throws Exception {
        String admin = login("b.wei@abc.edu");
        Student john = student("j.smith@abc.edu");
        Student eric = student("e.hudson@abc.edu");
        Student jerry = student("j.moon@abc.edu");
        eric.setEnabled(false);
        jerry.setEnabled(false);
        String week = "2023-W39";
        PeerEvaluation zero = new PeerEvaluation(week, john, eric,
                john.getSection().getRubric().getCriteria().stream().map(c -> new Rating(c, 0.0)).toList(),
                "zero public", "zero private");
        em.persist(zero);
        em.flush();
        var response = mvc.perform(get(API + "/evaluations/sections/" + eric.getSection().getSectionId() + "/week/" + week)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.peerEvaluationAverages[?(@.studentId == " + jerry.getId() + ")]").isEmpty())
                .andExpect(jsonPath("$.data.peerEvaluationAverages[?(@.studentId == " + john.getId() + ")]").isNotEmpty()).andReturn();
        var rows = json.readTree(response.getResponse().getContentAsString()).get("data").get("peerEvaluationAverages");
        var zeroRow = java.util.stream.StreamSupport.stream(rows.spliterator(), false)
                .filter(row -> row.get("studentId").asInt() == eric.getId()).findFirst().orElseThrow();
        assertThat(zeroRow.get("averageTotalScore").asDouble()).isZero();
        assertThat(zeroRow.get("publicComments").get(0).asText()).isEqualTo("zero public");
        assertThat(zeroRow.get("privateComments").get(0).asText()).isEqualTo("zero private");
        assertThat(evaluations.findByWeekAndEvaluateeSectionSectionId(week, eric.getSection().getSectionId()))
                .extracting(PeerEvaluation::getPeerEvaluationId).containsExactly(zero.getPeerEvaluationId());
        assertThat(evaluations.findByWeekAndEvaluateeSectionSectionId("2023-W40", eric.getSection().getSectionId())).isEmpty();
        assertThat(evaluations.findByWeekAndEvaluateeSectionSectionId(week, 1)).isEmpty();
    }

    @Test // T27-T29: requested-week history survives inactivity and later authorized team removal.
    void weeklyReportRetainsInactiveHistoryAndContributionsAfterTeamRemoval() throws Exception {
        String admin = login("b.wei@abc.edu");
        Student eric = student("e.hudson@abc.edu");
        int id = eric.getId();
        int sectionId = eric.getSection().getSectionId();
        String path = API + "/evaluations/sections/" + sectionId + "/week/2023-W31";
        String before = mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var original = json.readTree(before).get("data").get("peerEvaluationAverages");
        eric.setEnabled(false);
        em.flush();
        mvc.perform(delete(API + "/teams/" + eric.getTeam().getTeamId() + "/students/" + id)
                .header(HttpHeaders.AUTHORIZATION, admin)).andExpect(status().isOk());
        em.flush();
        em.clear();
        var response = mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, admin)).andExpect(status().isOk()).andReturn();
        var retained = json.readTree(response.getResponse().getContentAsString()).get("data").get("peerEvaluationAverages");
        for (var oldRow : original) {
            var row = java.util.stream.StreamSupport.stream(retained.spliterator(), false)
                    .filter(candidate -> candidate.get("studentId").asInt() == oldRow.get("studentId").asInt()).findFirst().orElseThrow();
            assertThat(row.get("averageTotalScore")).isEqualTo(oldRow.get("averageTotalScore"));
            assertThat(row.get("ratingAverages")).isEqualTo(oldRow.get("ratingAverages"));
            assertThat(row.get("publicComments")).isEqualTo(oldRow.get("publicComments"));
            assertThat(row.get("privateComments")).isEqualTo(oldRow.get("privateComments"));
        }
        var ericRow = java.util.stream.StreamSupport.stream(retained.spliterator(), false)
                .filter(row -> row.get("studentId").asInt() == id).findFirst().orElseThrow();
        // Independent DataInitializer W31 oracle: John->Eric 47, Eric->Eric 54, Jerry->Eric 49.
        assertThat(ericRow.get("averageTotalScore").asDouble()).isEqualTo(50.0);
        assertThat(java.util.stream.StreamSupport.stream(ericRow.get("publicComments").spliterator(), false)
                .map(node -> node.asText()).toList()).containsExactlyInAnyOrder(
                        "Eric did a great job this week! I think he is on track.", "As Eric, I just do my best!", "eric eric eric, go, go go!");
        assertThat(java.util.stream.StreamSupport.stream(ericRow.get("privateComments").spliterator(), false)
                .map(node -> node.asText()).toList()).containsExactlyInAnyOrder("John wrote this private comment!", "I am eric.", "I am Jerry.");
        var johnRow = java.util.stream.StreamSupport.stream(retained.spliterator(), false)
                .filter(row -> row.get("studentId").asInt() == 4).findFirst().orElseThrow();
        // John->John 51, now-inactive Eric->John 51, Jerry->John 52: keep Eric's contribution.
        assertThat(johnRow.get("averageTotalScore").asDouble()).isCloseTo(154.0 / 3, org.assertj.core.data.Offset.offset(0.000001));
        assertThat(em.find(Student.class, id).getTeam()).isNull();
        mvc.perform(get(API + "/evaluations/sections/" + sectionId + "/week/2023-W40").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.peerEvaluationAverages[?(@.studentId == " + id + ")]").isEmpty())
                .andExpect(jsonPath("$.data.peerEvaluationAverages[?(@.studentId == 4)]").isNotEmpty());
    }
}
