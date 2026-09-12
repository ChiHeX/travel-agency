<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { accountApi } from '@/api/modules'
import RequestState from '@/components/RequestState.vue'

const travelers = ref([])
const loading = ref(false)
const dialog = ref(false)
const editingId = ref(null)
const submitting = ref(false)
const error = ref('')
const submitError = ref('')

const form = reactive({
  name: '',
  gender: 'MALE',
  birthDate: '',
  idType: 'CHINESE_ID_CARD',
  idNo: '',
  phone: '',
  emergencyName: '',
  emergencyPhone: ''
})

function reset() {
  Object.assign(form, {
    name: '',
    gender: 'MALE',
    birthDate: '',
    idType: 'CHINESE_ID_CARD',
    idNo: '',
    phone: '',
    emergencyName: '',
    emergencyPhone: ''
  })
  editingId.value = null
}

function open(item) {
  submitError.value = ''
  reset()
  if (item) {
    editingId.value = item.id
    Object.assign(form, {
      name: item.name,
      gender: item.gender,
      birthDate: item.birthDate,
      idType: item.idType,
      idNo: '',
      phone: item.phone || '',
      emergencyName: item.emergencyName,
      emergencyPhone: item.emergencyPhone
    })
  }
  dialog.value = true
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    travelers.value = (await accountApi.travelers()) || []
  } catch (cause) { error.value = cause.message || '出行人加载失败'
  } finally {
    loading.value = false
  }
}

async function save() {
  if (submitting.value) return
  submitError.value = ''
  if (!form.name || !form.birthDate || (!form.idNo && !editingId.value) || !form.emergencyName || !form.emergencyPhone) {
    return ElMessage.warning('请完整填写姓名、出生日期、证件号码和紧急联系人')
  }
  const payload = {
    name: form.name,
    gender: form.gender,
    birthDate: form.birthDate,
    idType: form.idType,
    phone: form.phone || null,
    emergencyName: form.emergencyName,
    emergencyPhone: form.emergencyPhone,
    ...(form.idNo ? { idNo: form.idNo } : {})
  }
  submitting.value = true
  try {
  if (editingId.value) await accountApi.updateTraveler(editingId.value, payload)
  else await accountApi.createTraveler(payload)
  dialog.value = false
  ElMessage.success('出行人资料已保存')
  load()
  } catch (cause) { submitError.value = cause.message || '保存失败，请重试' }
  finally { submitting.value = false }
}

async function remove(item) {
  if (submitting.value) return
  try {
  await ElMessageBox.confirm(`确认删除 ${item.name} 的常用出行人资料吗？`, '删除资料', {
    type: 'warning',
    confirmButtonText: '确认删除',
    cancelButtonText: '取消'
  })
  submitting.value = true
  await accountApi.deleteTraveler(item.id)
  ElMessage.success('已删除')
  load()
  } catch (cause) { if (cause !== 'cancel' && cause !== 'close') error.value = cause.message || '删除失败' }
  finally { submitting.value = false }
}

onMounted(load)
</script>

<template>
  <div class="account-page">
    <div class="container narrow-container page-section">
      <div class="section-head">
        <div>
          <span class="eyebrow">SAVED TRAVELERS</span>
          <h2>常用出行人管理</h2>
          <p>提前保存出行人资料，报名下单时可一键带入；已生成历史订单不受后续修改影响。</p>
        </div>
        <button type="button" class="primary-button" @click="open()">
          + 新增出行人
        </button>
      </div>

      <div class="admin-panel">
        <RequestState v-if="error" :error="error" @retry="load" />
        <div v-else-if="loading">
          <el-skeleton :rows="5" animated />
        </div>

        <table v-else-if="travelers.length" class="data-table responsive-cards">
          <thead>
            <tr>
              <th>出行人姓名</th>
              <th>性别</th>
              <th>证件类型与脱敏号</th>
              <th>联系电话</th>
              <th>紧急联系人</th>
              <th style="text-align: right;">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in travelers" :key="item.id">
              <td data-label="姓名"><strong>{{ item.name }}</strong></td>
              <td data-label="性别">{{ { MALE: '男', FEMALE: '女', OTHER: '其他' }[item.gender] || item.gender }}</td>
              <td data-label="证件">{{ { CHINESE_ID_CARD: '身份证', PASSPORT: '护照', OTHER: '其他证件' }[item.idType] || item.idType }} {{ item.idNoMasked }}</td>
              <td data-label="联系电话">{{ item.phone || '—' }}</td>
              <td data-label="紧急联系人">{{ item.emergencyName }} ({{ item.emergencyPhone || '—' }})</td>
              <td data-label="操作" style="text-align: right;">
                <button type="button" class="text-button" @click="open(item)">编辑</button>
                <span class="action-divider">|</span>
                <button type="button" class="text-button delete-btn" @click="remove(item)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>

        <div v-else class="empty-box">
          暂无常用出行人资料，点击右上角“+ 新增出行人”添加常用朋友或家人。
        </div>
      </div>
    </div>

    <!-- Edit Dialog -->
    <el-dialog
      append-to-body
      class="traveler-dialog"
      v-model="dialog"
      :title="editingId ? '编辑常用出行人' : '新增常用出行人'"
      width="min(560px, calc(100vw - 32px))"
      :close-on-click-modal="!submitting"
      :show-close="!submitting"
    >
      <div class="dialog-form-grid">
        <p v-if="submitError" class="form-error wide" role="alert">{{ submitError }}</p>
        <div class="form-field">
          <label>真实姓名 <span class="req">*</span></label>
          <input v-model="form.name" placeholder="请与证件姓名一致" required />
        </div>

        <div class="form-field">
          <label>性别</label>
          <select v-model="form.gender">
            <option value="MALE">男</option>
            <option value="FEMALE">女</option>
            <option value="OTHER">其他</option>
          </select>
        </div>

        <div class="form-field">
          <label>出生日期</label>
          <input v-model="form.birthDate" type="date" />
        </div>

        <div class="form-field">
          <label>证件类型</label>
          <select v-model="form.idType">
            <option value="CHINESE_ID_CARD">身份证</option>
            <option value="PASSPORT">护照</option>
            <option value="OTHER">其他证件</option>
          </select>
        </div>

        <div class="form-field wide">
          <label>证件号码 <span class="req">*</span></label>
          <input
            v-model="form.idNo"
            :placeholder="editingId ? '已加密隐藏，如需更改请重新输入' : '请输入完整有效证件号'"
          />
        </div>

        <div class="form-field">
          <label>联系电话</label>
          <input v-model="form.phone" inputmode="tel" placeholder="可选填写" />
        </div>

        <div class="form-field">
          <label>紧急联系人姓名 <span class="req">*</span></label>
          <input v-model="form.emergencyName" placeholder="如：家属 / 朋友" required />
        </div>

        <div class="form-field wide">
          <label>紧急联系人电话</label>
          <input v-model="form.emergencyPhone" inputmode="tel" placeholder="紧急联络号码" />
        </div>
      </div>

      <template #footer>
        <button class="secondary-button" :disabled="submitting" @click="dialog = false">取消</button>
        <button class="primary-button" :disabled="submitting" @click="save">{{ submitting ? '保存中…' : '保存出行人' }}</button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.account-page {
  background: var(--bg-canvas);
  min-height: calc(100vh - 64px);
}

.action-divider {
  color: var(--border-strong);
  margin: 0 6px;
  font-size: 11px;
}

.delete-btn {
  color: var(--danger-text);
}

.delete-btn:hover {
  color: var(--danger-red);
}

.dialog-form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.dialog-form-grid .wide {
  grid-column: 1 / -1;
}

.req {
  color: var(--danger-red);
}

@media (max-width: 600px) {
  .dialog-form-grid {
    grid-template-columns: 1fr;
  }
}
</style>

<style>
@media (max-width: 640px) {
  .traveler-dialog { margin-top: 5dvh; }
  .traveler-dialog .el-dialog__body { max-height: 65dvh; overflow-y: auto; }
}
</style>
