<template>
  <el-form
    ref="studentForm"
    :model="studentBeingEdited"
    :rules="rules"
    label-width="110px"
    style="padding-right: 30px"
    label-position="left"
  >
    <el-form-item label="Id:">
      <el-input v-model="studentBeingEdited.id" disabled></el-input>
    </el-form-item>
    <el-form-item label="Username:" prop="username">
      <el-input v-model="studentBeingEdited.username"></el-input>
    </el-form-item>
    <el-form-item label="Status:" prop="enabled">
      <el-tag data-cy="student-status" :type="studentBeingEdited.enabled ? 'success' : 'danger'">
        {{ studentBeingEdited.enabled ? 'Active' : 'Inactive' }}
      </el-tag>
    </el-form-item>
    <el-form-item label="First Name:" prop="firstName">
      <el-input v-model="studentBeingEdited.firstName"></el-input>
    </el-form-item>
    <el-form-item label="Last Name:" prop="lastName">
      <el-input v-model="studentBeingEdited.lastName"></el-input>
    </el-form-item>
    <el-form-item label="Email:" prop="email">
      <el-input v-model="studentBeingEdited.email"></el-input>
    </el-form-item>
    <el-form-item label="Team:" prop="teamName">
      <el-input v-model="studentBeingEdited.teamName" disabled></el-input>
    </el-form-item>
    <p v-if="operationMessage" role="alert" data-cy="deactivation-result">{{ operationMessage }}</p>
    <el-button v-if="unknownOutcome" data-cy="deactivation-check-status" :loading="reconciling"
      @click="reconcileStatus">Check current status</el-button>
    <el-button type="danger" plain data-cy="deactivate-student"
      :disabled="!statusKnown || !persistedStudent?.enabled || isDeactivating || reconciling"
      @click="warningVisible = true">Deactivate student</el-button>
    <div style="display: flex; justify-content: end; margin-top: 16px">
      <el-button :disabled="isDeactivating || reconciling" @click="$emit('close-dialog')"> Cancel </el-button>
      <el-button type="primary" :disabled="!statusKnown || isDeactivating || reconciling" @click="updateExistingStudent()"> Confirm </el-button>
    </div>
  </el-form>
  <el-dialog v-model="warningVisible" title="Deactivate student?" width="min(560px, 90vw)"
    append-to-body data-cy="deactivation-warning" :show-close="!isDeactivating"
    :close-on-click-modal="!isDeactivating" :close-on-press-escape="!isDeactivating">
    <p>Deactivate <strong>{{ persistedStudent?.firstName }} {{ persistedStudent?.lastName }}</strong>
      ({{ persistedStudent?.email }})?</p>
    <ul>
      <li>The student will lose access, including access with an existing login session.</li>
      <li>Course section and team membership will be retained.</li>
      <li>Submitted activities, peer evaluations, scores and comments will be retained.</li>
      <li>The inactive student will owe no weekly activity reports or peer evaluations and will receive no submission reminders.</li>
      <li>The student will be excluded from peer evaluation entry; new evaluations and edits about this student will be refused.</li>
      <li>Historical evaluations remain available in reports for weeks with received evaluations.</li>
    </ul>
    <template #footer>
      <el-button data-cy="deactivation-cancel" :disabled="isDeactivating" @click="warningVisible = false">Cancel</el-button>
      <el-button type="danger" data-cy="deactivation-confirm" :loading="isDeactivating" :disabled="isDeactivating"
        @click="confirmDeactivation">Confirm deactivation</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ElMessage } from 'element-plus'
import type { Student } from '@/apis/student/types'
import type { FormInstance } from 'element-plus'
import { deactivateStudent, findStudentById, updateStudent } from '@/apis/student'
import { ref, onMounted } from 'vue'
import { isAxiosError } from 'axios'

const { studentId } = defineProps(['studentId'])
const emit = defineEmits(['close-dialog', 'student-deactivated', 'busy-change'])
const persistedStudent = ref<Student>()
const statusKnown = ref(false)
const warningVisible = ref(false)
const isDeactivating = ref(false)
const reconciling = ref(false)
const unknownOutcome = ref(false)
const operationMessage = ref('')

// Note that the structure of the userData is NOT the same as the User interface since the roles are stored as an array
const studentBeingEdited = ref<Student>({
  // Data of the user being added or edited
  id: NaN,
  username: '',
  enabled: false,
  firstName: '',
  lastName: '',
  email: ''
})

const studentForm = ref<FormInstance>() // Form reference

// Validation rules
const rules = {
  username: [
    { required: true, message: 'Please input your email as your username', trigger: 'blur' },
    {
      pattern: /^[^\s@]+@[^\s@]+\.[^\s@]+$/,
      message: 'Enter your email as username.',
      trigger: 'blur'
    }
  ],
  firstName: [
    { required: true, message: 'Please provide the first name of the user.', trigger: 'blur' }
  ],
  lastName: [
    { required: true, message: 'Please provide the last name of the user.', trigger: 'blur' }
  ],
  email: [
    { required: true, message: 'Please provide the email of the user.', trigger: 'blur' },
    { type: 'email', message: 'Please provide a valid email.', trigger: 'blur' }
  ]
}

async function loadStudent(studentId: number) {
  // Load the user to be edited
  const result = await findStudentById(studentId)

  persistedStudent.value = { ...result.data }
  statusKnown.value = true
  studentBeingEdited.value.id = result.data.id as number
  studentBeingEdited.value.username = result.data.username
  studentBeingEdited.value.enabled = result.data.enabled
  studentBeingEdited.value.firstName = result.data.firstName
  studentBeingEdited.value.lastName = result.data.lastName
  studentBeingEdited.value.email = result.data.email
  studentBeingEdited.value.teamName = result.data.teamName
}

onMounted(() => {
  loadStudent(studentId).catch(() => { operationMessage.value = 'Student details are unavailable.' })
})

async function reconcileStatus() {
  reconciling.value = true
  emit('busy-change', true)
  try {
    const result = await findStudentById(studentId)
    persistedStudent.value = { ...result.data }
    studentBeingEdited.value.enabled = result.data.enabled
    statusKnown.value = true
    unknownOutcome.value = false
    operationMessage.value = result.data.enabled
      ? 'Current status is active. Review the warning before another attempt.'
      : 'Current status is inactive. The student is deactivated.'
    if (!result.data.enabled) emit('student-deactivated')
  } catch {
    statusKnown.value = false
    operationMessage.value = 'Deactivation outcome is unknown. Check current status before another attempt.'
  } finally {
    reconciling.value = false
    emit('busy-change', false)
  }
}

async function confirmDeactivation() {
  if (isDeactivating.value || !statusKnown.value || !persistedStudent.value?.enabled) return
  isDeactivating.value = true
  emit('busy-change', true)
  operationMessage.value = ''
  try {
    const result = await deactivateStudent(studentId)
    persistedStudent.value = { ...result.data }
    studentBeingEdited.value.enabled = result.data.enabled
    warningVisible.value = false
    operationMessage.value = result.message
    ElMessage.success(result.message)
    emit('student-deactivated')
  } catch (error) {
    warningVisible.value = false
    if (!isAxiosError(error) || !error.response || (error.response.status === 408 || error.response.status >= 500)) {
      statusKnown.value = false
      unknownOutcome.value = true
      operationMessage.value = 'Deactivation outcome is unknown. Checking current account status.'
      await reconcileStatus()
    } else {
      operationMessage.value = 'Deactivation failed. The account status has not been changed.'
    }
  } finally {
    isDeactivating.value = false
    emit('busy-change', false)
  }
}

async function updateExistingStudent() {
  await studentForm.value!.validate() // Validate the form

  const updatedStudent: Student = {
    id: studentBeingEdited.value.id,
    username: studentBeingEdited.value.username,
    enabled: studentBeingEdited.value.enabled,
    firstName: studentBeingEdited.value.firstName,
    lastName: studentBeingEdited.value.lastName,
    email: studentBeingEdited.value.email
  }
  console.log(updatedStudent)
  // Call the API to update the user
  await updateStudent(updatedStudent)

  ElMessage.success('Student updated successfully')
  emit('close-dialog')
}
</script>

<style scoped lang="scss"></style>
