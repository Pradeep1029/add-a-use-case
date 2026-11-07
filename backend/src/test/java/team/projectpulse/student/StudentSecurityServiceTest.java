package team.projectpulse.student;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import team.projectpulse.course.Course;
import team.projectpulse.instructor.Instructor;
import team.projectpulse.section.Section;
import team.projectpulse.system.UserUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** UC-STU-deactivate-student shared scope: T03-T05, T09 and T30. */
@org.junit.jupiter.api.Tag("UC-STU-deactivate-student")
class StudentSecurityServiceTest {
    private final StudentRepository students = mock(StudentRepository.class);
    private final UserUtils caller = mock(UserUtils.class);
    private final StudentSecurityService security = new StudentSecurityService(students, caller);
    private final Student target = new Student();
    private final Section section = new Section();
    private final Course course = new Course();
    private final Instructor owner = new Instructor();

    @BeforeEach
    void setUp() {
        owner.setId(1);
        course.setCourseAdmin(owner);
        course.addSection(section);
        section.addStudent(target);
        when(students.findById(3)).thenReturn(Optional.of(target));
    }

    @Test
    void owningAdminNeedsNoSectionAssignment() {
        when(caller.getUserId()).thenReturn(1);
        when(caller.hasRole("ROLE_admin")).thenReturn(true);
        assertThat(section.getInstructors()).isEmpty();
        assertThat(security.isCurrentUserInstructorOfStudentSection(3)).isTrue();
    }

    @Test
    void assignedInstructorPassesButUnassignedAndOtherCourseAdminDoNot() {
        Instructor assigned = new Instructor();
        assigned.setId(2);
        section.addInstructor(assigned);
        when(caller.hasRole("ROLE_instructor")).thenReturn(true);
        when(caller.getUserId()).thenReturn(2, 99);
        assertThat(security.isCurrentUserInstructorOfStudentSection(3)).isTrue();
        assertThat(security.isCurrentUserInstructorOfStudentSection(3)).isFalse();
        when(caller.hasRole("ROLE_admin")).thenReturn(true);
        assertThat(security.isCurrentUserInstructorOfStudentSection(3)).isFalse();
    }

    @Test
    void studentsCannotAcquireInstructorScopeEvenWithMatchingId() {
        when(caller.getUserId()).thenReturn(1);
        assertThat(security.isCurrentUserInstructorOfStudentSection(3)).isFalse();
    }

    @Test
    void unresolvedIdentityTargetAndLinksFailClosed() {
        assertThat(security.isCurrentUserInstructorOfStudentSection(null)).isFalse();
        assertThat(security.isCurrentUserInstructorOfStudentSection(3)).isFalse();
        when(caller.getUserId()).thenReturn(1);
        when(caller.hasRole("ROLE_admin")).thenReturn(true);
        assertThat(security.isCurrentUserInstructorOfStudentSection(999)).isFalse();
        target.setSection(null);
        assertThat(security.isCurrentUserInstructorOfStudentSection(3)).isFalse();
        target.setSection(section);
        section.setCourse(null);
        assertThat(security.isCurrentUserInstructorOfStudentSection(3)).isFalse();
    }
}
