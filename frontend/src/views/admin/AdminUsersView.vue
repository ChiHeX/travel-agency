<script setup>
import { onMounted, ref } from 'vue'
import { adminApi } from '@/api/modules'

const props = defineProps({ mode: { type: String, default: 'users' } })
const rows = ref([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const data =
      props.mode === 'staff'
        ? await adminApi.staff({ page: 1, size: 20 })
        : await adminApi.users({ page: 1, size: 20 })
    rows.value = data?.items || []
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="admin-users-page">
    <div class="admin-page-head">
      <div>
        <h2>{{ props.mode === 'staff' ? '内部员工权限管理' : '注册用户账户管理' }}</h2>
        <p>{{ props.mode === 'staff' ? '管理员负责内部运营、客服与导游账号角色分配。' : '查看注册游客基础资料与账户状态，不直接接触敏感密码。' }}</p>
      </div>
    </div>

    <div class="admin-panel">
      <div v-if="loading">
        <el-skeleton :rows="8" animated />
      </div>

      <table v-else-if="rows.length" class="data-table">
        <thead>
          <tr v-if="props.mode === 'staff'">
            <th>员工工号</th>
            <th>用户账号 ID</th>
            <th>所属部门</th>
            <th>职级岗位</th>
            <th>入职/创建时间</th>
          </tr>
          <tr v-else>
            <th>账号用户名</th>
            <th>用户昵称</th>
            <th>联系电话</th>
            <th>电子邮箱</th>
            <th>账号状态</th>
            <th>注册时间</th>
          </tr>
        </thead>
        <tbody>
          <template v-if="props.mode === 'staff'">
            <tr v-for="row in rows" :key="row.id">
              <td><strong>{{ row.employeeNo }}</strong></td>
              <td>{{ row.userId }}</td>
              <td>{{ row.department || '—' }}</td>
              <td>{{ row.position || '—' }}</td>
              <td>{{ row.createdAt }}</td>
            </tr>
          </template>
          <template v-else>
            <tr v-for="row in rows" :key="row.id">
              <td><strong>{{ row.username }}</strong></td>
              <td>{{ row.nickname }}</td>
              <td>{{ row.phone || '—' }}</td>
              <td>{{ row.email || '—' }}</td>
              <td>
                <span class="tag" :class="row.status === 'ACTIVE' ? 'success' : 'danger'">
                  {{ row.status === 'ACTIVE' ? '正常使用' : '已冻结' }}
                </span>
              </td>
              <td>{{ row.createdAt }}</td>
            </tr>
          </template>
        </tbody>
      </table>

      <div v-else class="empty-box">
        暂无用户数据。
      </div>
    </div>
  </div>
</template>

<style scoped>
</style>
