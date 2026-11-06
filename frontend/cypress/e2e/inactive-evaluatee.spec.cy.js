// UC-STU-deactivate-student T25-T26, BR-evaluation-active-evaluatee. All APIs are stubbed.
const self = { id: 4, firstName: 'John', lastName: 'Smith', username: 'j.smith@abc.edu', email: 'j.smith@abc.edu', enabled: true, roles: 'student', teamId: 1, sectionId: 2 }
const eric = { ...self, id: 5, firstName: 'Eric', lastName: 'Hudson' }
const jerry = { ...self, id: 6, firstName: 'Jerry', lastName: 'Moon' }
const result = (data) => ({ flag: true, code: 200, message: 'OK', data })
const evaluation = (student) => ({ evaluationId: student.id + 100, evaluateeId: student.id,
  evaluatorId: 4, evaluateeName: `${student.firstName} ${student.lastName}`, week: '2026-W44',
  ratings: [{ criterionId: 1, criterion: 'Quality', actualScore: 8 }], publicComment: `Saved ${student.firstName}`, privateComment: 'Private history' })
function visitEntry() {
  const token = `${btoa('{}')}.${btoa(JSON.stringify({ exp: 4102444800 }))}.stub`
  cy.visit('/evaluations/submit-evaluations', { onBeforeLoad(win) {
    win.localStorage.setItem('token', JSON.stringify({ token }))
    win.localStorage.setItem('userInfo', JSON.stringify({ userInfo: self }))
  } })
  cy.wait('@roster')
  cy.wait('@saved')
}
function stubCriteria() {
  cy.intercept('GET', '**/sections/2', { body: result({ rubricId: 1 }) })
  cy.intercept('GET', '**/rubrics/1', { body: result({ criteria: [{ criterionId: 1, criterion: 'Quality', description: 'Quality', maxScore: 10 }] }) })
  cy.intercept('POST', '**/evaluations/weeks/*/receipt', { body: result(null) }).as('receipt')
  cy.intercept('DELETE', '**/evaluations/**', { statusCode: 500 }).as('deleteHistory')
}

describe('Active evaluatees in new and saved entry forms', () => {
  beforeEach(stubCriteria)

  it('T25 blank entry includes active teammates/self and sends no inactive target', () => {
    cy.intercept('GET', '**/students/teams/1', { body: result([self, eric, { ...jerry, enabled: false }]) }).as('roster')
    cy.intercept('GET', '**/evaluations/evaluators/4/week/*', { body: result([]) }).as('saved')
    cy.intercept('POST', '**/evaluations', (req) => req.reply({ body: result({ ...req.body, evaluationId: req.body.evaluateeId + 100 }) })).as('create')
    visitEntry()
    cy.get('.el-table__body-wrapper').should('contain', 'John Smith').and('contain', 'Eric Hudson').and('not.contain', 'Jerry Moon')
    cy.contains('button', 'Submit').click()
    cy.wait('@receipt')
    cy.get('@create.all').should('have.length', 2).then((calls) => {
      expect(calls.map((call) => call.request.body.evaluateeId).sort()).to.deep.equal([4, 5])
    })
    cy.get('@deleteHistory.all').should('have.length', 0)
  })

  it('T25 saved inactive entries are absent while a missing active entry remains editable', () => {
    cy.intercept('GET', '**/students/teams/1', { body: result([self, eric, { ...jerry, enabled: false }]) }).as('roster')
    cy.intercept('GET', '**/evaluations/evaluators/4/week/*', { body: result([evaluation(self), evaluation(jerry)]) }).as('saved')
    visitEntry()
    cy.get('.el-table__body-wrapper').should('contain', 'John Smith').and('contain', 'Eric Hudson').and('not.contain', 'Jerry Moon')
    cy.get('textarea').first().should('have.value', 'Saved John')
    cy.get('@deleteHistory.all').should('have.length', 0)
  })

  for (const editing of [false, true]) {
    it(`T26 inactive refusal refreshes eligibility for ${editing ? 'saved' : 'new'} entries without claiming all saved`, () => {
      let inactive = false
      const stored = editing ? [evaluation(self), evaluation(eric), evaluation(jerry)] : []
      cy.intercept('GET', '**/students/teams/1', (req) => req.reply({ body: result([self, eric, { ...jerry, enabled: !inactive }]) })).as('roster')
      cy.intercept('GET', '**/evaluations/evaluators/4/week/*', (req) => req.reply({ body: result(stored) })).as('saved')
      cy.intercept(editing ? 'PUT' : 'POST', editing ? '**/evaluations/*' : '**/evaluations', (req) => {
        if (req.body.evaluateeId === 6) {
          inactive = true
          req.reply({ statusCode: 400, body: { flag: false, code: 400, message: 'Cannot evaluate inactive student Jerry Moon.' } })
        } else {
          const saved = { ...req.body, evaluationId: req.body.evaluateeId + 100 }
          const index = stored.findIndex((entry) => entry.evaluateeId === saved.evaluateeId)
          if (index < 0) stored.push(saved)
          else stored[index] = saved
          req.reply({ body: result(saved) })
        }
      }).as('save')
      visitEntry()
      cy.get('.el-table__body-wrapper').should('contain', 'Jerry Moon')
      cy.contains('button', editing ? 'Update' : 'Submit').click()
      cy.get('[data-cy=evaluation-save-error]').should('contain', 'eligible team members changed')
      cy.get('.el-table__body-wrapper').should('not.contain', 'Jerry Moon').and('contain', 'John Smith').and('contain', 'Eric Hudson')
      cy.get('@roster.all').should('have.length', 2)
      cy.get('.el-message--success').should('not.exist')
      cy.get('@deleteHistory.all').should('have.length', 0)
      cy.then(() => {
        const historical = stored.find((entry) => entry.evaluateeId === 6)
        if (editing) expect(historical.publicComment).to.equal('Saved Jerry')
        else expect(historical).to.equal(undefined)
      })
    })
  }
})
