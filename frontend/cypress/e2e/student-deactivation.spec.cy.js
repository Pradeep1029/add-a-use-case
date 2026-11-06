// UC-STU-deactivate-student T01, T11, T14-T16. API-stubbed UI integration tests.
const target = { id: 4, username: 'j.smith@abc.edu', firstName: 'John', lastName: 'Smith',
  email: 'j.smith@abc.edu', enabled: true, teamId: 1, teamName: 'Team1', sectionId: 2 }
const result = (data, message = 'OK') => ({ flag: true, code: 200, message, data })
const page = (student) => result({ content: [student], number: 0, size: 50, totalElements: 1 })
function visitAsInstructor() {
  const token = `${btoa('{}')}.${btoa(JSON.stringify({ exp: 4102444800 }))}.stub`
  cy.visit('/students', { onBeforeLoad(win) {
    win.localStorage.setItem('token', JSON.stringify({ token }))
    win.localStorage.setItem('userInfo', JSON.stringify({ userInfo: { id: 2, username: 'b.gates@abc.edu', firstName: 'Bill', lastName: 'Gates', roles: 'instructor' } }))
    win.localStorage.setItem('settings', JSON.stringify({ defaultSectionId: 2, defaultCourseId: 1 }))
  } })
  cy.wait('@students')
  cy.get('.el-table__body-wrapper .el-button').first().click()
  cy.wait('@details')
  cy.get('[data-cy=deactivate-student]').click()
  cy.get('[data-cy=deactivation-warning]').should('be.visible')
}

describe('Student deactivation confirmation', () => {
  beforeEach(() => {
    cy.intercept('POST', '**/students/search*', { body: page(target) }).as('students')
    cy.intercept('GET', '**/students/4', { body: result(target) }).as('details')
    cy.intercept('POST', '**/students/4/deactivation', { delay: 700, body: result({ ...target, enabled: false }, 'Student deactivated successfully') }).as('deactivation')
  })

  it('T01 names the persisted student and all consequences before any request', () => {
    visitAsInstructor()
    cy.get('[data-cy=deactivation-warning]').should('contain', 'John Smith').and('contain', target.email)
      .and('contain', 'existing login session').and('contain', 'team membership')
      .and('contain', 'scores and comments').and('contain', 'no submission reminders')
      .and('contain', 'new evaluations and edits').and('contain', 'Historical evaluations')
    cy.get('@deactivation.all').should('have.length', 0)
    cy.screenshot('student-deactivation-confirmation', { capture: 'viewport' })
  })

  for (const dismissal of ['Cancel', 'Escape', 'close']) {
    it(`T11 ${dismissal} dismisses without a request or status change`, () => {
      visitAsInstructor()
      if (dismissal === 'Cancel') cy.get('[data-cy=deactivation-cancel]').click()
      else if (dismissal === 'Escape') cy.get('body').type('{esc}')
      else cy.get('[data-cy=deactivation-warning] .el-dialog__headerbtn').click()
      cy.get('[data-cy=deactivation-warning]').should('not.be.visible')
      cy.get('@deactivation.all').should('have.length', 0)
      cy.get('[data-cy=student-status]').should('contain', 'Active')
    })
  }

  it('T16 sends one bodyless request despite a second Confirm while pending, then refreshes the list', () => {
    visitAsInstructor()
    cy.intercept('POST', '**/students/search*', { body: page({ ...target, enabled: false }) }).as('refreshedStudents')
    cy.get('[data-cy=deactivation-confirm]').click()
    cy.get('[data-cy=deactivation-confirm]').should('be.disabled').click({ force: true })
    cy.wait('@deactivation').then(({ request }) => expect(request.body ?? '').to.equal(''))
    cy.wait('@refreshedStudents')
    cy.get('[data-cy=student-status]').should('contain', 'Inactive')
    cy.get('[data-cy=deactivate-student]').should('be.disabled')
    cy.get('@deactivation.all').should('have.length', 1)
  })

  it('T14 a server error reconciles active status without claiming success', () => {
    cy.intercept('POST', '**/students/4/deactivation', { statusCode: 500, body: { flag: false, code: 500, message: 'Persistence failed' } }).as('failed')
    visitAsInstructor()
    cy.get('[data-cy=deactivation-confirm]').click()
    cy.wait('@failed')
    cy.get('[data-cy=deactivation-result]').should('contain', 'Current status is active')
    cy.get('[data-cy=student-status]').should('contain', 'Active')
    cy.get('.el-message--success').should('not.exist')
  })

  it('T14 a known refusal never claims success or changes local status', () => {
    visitAsInstructor()
    cy.intercept('POST', '**/students/4/deactivation', { statusCode: 400, body: { flag: false, message: 'Action refused' } }).as('refused')
    cy.get('[data-cy=deactivation-confirm]').click()
    cy.wait('@refused')
    cy.get('[data-cy=deactivation-result]').should('contain', 'Deactivation failed')
    cy.get('[data-cy=student-status]').should('contain', 'Active')
    cy.get('.el-message--success').should('not.exist')
    cy.get('@details.all').should('have.length', 1)
  })

  it('T15 a 503 can follow a committed action and requires GET reconciliation', () => {
    visitAsInstructor()
    cy.intercept('POST', '**/students/4/deactivation', { statusCode: 503, body: { flag: false, message: 'Gateway unavailable' } }).as('uncertain')
    cy.intercept('GET', '**/students/4', { delay: 600, body: result({ ...target, enabled: false }) }).as('freshStatus')
    cy.get('[data-cy=deactivation-confirm]').click()
    cy.get('[data-cy=deactivate-student]').should('be.disabled')
    cy.wait('@freshStatus', { timeout: 15000 })
    cy.get('[data-cy=student-status]').should('contain', 'Inactive')
    cy.get('[data-cy=deactivate-student]').should('be.disabled')
    cy.get('@uncertain.all').should('have.length', 1)
    cy.get('.el-message--success').should('not.exist')
  })

  // Delay beyond the shared Axios10s timeout; connection-reset fixtures can trigger Chromium transport retries.
  for (const enabled of [true, false]) {
    it(`T15 lost response reconciles persisted enabled=${enabled} by GET without POST retry`, () => {
      visitAsInstructor()
      cy.intercept('POST', '**/students/4/deactivation', { delay: 11000, body: result({ ...target, enabled: false }) }).as('lost')
      cy.intercept('GET', '**/students/4', { delay: 600, body: result({ ...target, enabled }) }).as('freshStatus')
      cy.get('[data-cy=deactivation-confirm]').click()
      cy.get('[data-cy=deactivate-student]').should('be.disabled')
      cy.wait('@freshStatus', { timeout: 15000 })
      cy.get('[data-cy=student-status]').should('contain', enabled ? 'Active' : 'Inactive')
      cy.get('[data-cy=deactivate-student]').should(enabled ? 'be.enabled' : 'be.disabled')
      cy.get('@lost.all').should('have.length', 1)
      cy.get('.el-message--success').should('not.exist')
    })
  }

  it('T15 a failed status refresh keeps the action disabled until a later successful GET', () => {
    visitAsInstructor()
    cy.intercept('POST', '**/students/4/deactivation', { delay: 11000, body: result({ ...target, enabled: false }) }).as('lost')
    cy.intercept('GET', '**/students/4', { statusCode: 500, body: { flag: false, message: 'Unavailable' } }).as('failedStatus')
    cy.get('[data-cy=deactivation-confirm]').click()
    cy.wait('@failedStatus', { timeout: 15000 })
    cy.get('[data-cy=deactivation-result]').should('contain', 'outcome is unknown')
    cy.get('[data-cy=student-status]').should('contain', 'Unknown')
    cy.get('[data-cy=deactivate-student]').should('be.disabled')
    cy.get('@lost.all').should('have.length', 1)
    cy.intercept('GET', '**/students/4', { body: result(target) }).as('laterStatus')
    cy.get('[data-cy=deactivation-check-status]').click()
    cy.wait('@laterStatus')
    cy.get('[data-cy=deactivate-student]').should('be.enabled')
    cy.get('@lost.all').should('have.length', 1)
  })
})
