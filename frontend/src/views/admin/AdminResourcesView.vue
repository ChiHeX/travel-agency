<script setup>
import { onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '@/api/modules'

const props = defineProps({
  title: { type: String, required: true },
  resource: { type: String, required: true }
})

const rows = ref([])
const loading = ref(false)

const loaders = {
  attractions: adminApi.attractions,
  hotels: adminApi.hotels,
  guides: adminApi.guides,
  departures: adminApi.departures,
  refunds: adminApi.refunds
}

async function load() {
  loading.value = true
  try {
    rows.value = (await loaders[props.resource]())?.items || []
  } finally {
    loading.value = false
  }
}

async function updateDeparture(row) {
  const next = row.status === 'OPEN' ? 'CLOSED' : 'OPEN'
  await adminApi.updateDepartureStatus(row.id, next)
  row.status = next
  ElMessage.success('团期状态已更新')
}

async function decision(row, action) {
  const comment = action === 'APPROVE' ? '审核通过，已进入原路退款流程' : '申请原因需要进一步核实'
  if (action === 'APPROVE') await adminApi.approveRefund(row.id, comment)
  else await adminApi.rejectRefund(row.id, comment)
  ElMessage.success('退款审核已处理完毕')
  load()
}

watch(() => props.resource, load)
onMounted(load)
</script>

<template>
  <div class="admin-resources-page">
    <div class="admin-page-head">
      <div>
        <h2>{{ title }}</h2>
        <p>维护基础业务资源档案、可追溯资料与审核流。</p>
      </div>
      <button
        v-if="resource !== 'refunds'"
        class="primary-button"
        @click="ElMessage.info('新增表单已对接对应后端 CRUD API')"
      >
        + 新增{{ title.replace('管理', '').replace('资料', '') }}
      </button>
    </div>

    <div class="admin-panel">
      <div v-if="loading">
        <el-skeleton :rows="8" animated />
      </div>

      <table v-else-if="rows.length" class="data-table">
        <thead>
          <tr v-if="resource === 'departures'">
            <th>线路编号</th>
            <th>出发日期</th>
            <th>返程日期</th>
            <th>成人价格</th>
            <th>已确认 / 最大容纳</th>
            <th>状态</th>
            <th style="text-align: right;">操作</th>
          </tr>
          <tr v-else-if="resource === 'refunds'">
            <th>退款申请单号</th>
            <th>关联订单号</th>
            <th>申请金额</th>
            <th>申请退款原因</th>
            <th>审核状态</th>
            <th style="text-align: right;">操作</th>
          </tr>
          <tr v-else-if="resource === 'guides'">
            <th>导游姓名</th>
            <th>联系电话</th>
            <th>个人专长简介</th>
            <th>状态</th>
          </tr>
          <tr v-else>
            <th>名称</th>
            <th>所属城市 / 地址</th>
            <th>地理经纬度</th>
            <th>资料来源</th>
            <th>状态</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="row in rows" :key="row.id">
            <tr v-if="resource === 'departures'">
              <td><strong>线路 #{{ row.routeId }}</strong></td>
              <td>{{ row.startDate }}</td>
              <td>{{ row.endDate }}</td>
              <td class="amount">¥{{ row.adultPrice }}</td>
              <td>{{ row.confirmedPeople == null ? '—' : row.confirmedPeople }} / {{ row.maxPeople == null ? '—' : row.maxPeople }}<span v-if="row.confirmedPeople != null || row.maxPeople != null"> 人</span></td>
              <td>
                <span class="tag" :class="row.status === 'OPEN' ? 'success' : row.status === 'CLOSED' ? 'danger' : 'warning'">
                  {{ row.status }}
                </span>
              </td>
              <td style="text-align: right;">
                <button type="button" class="text-button" @click="updateDeparture(row)">
                  {{ row.status === 'OPEN' ? '关闭报名' : '开放报名' }}
                </button>
              </td>
            </tr>

            <tr v-else-if="resource === 'refunds'">
              <td><strong>#{{ row.id }}</strong></td>
              <td>#{{ row.orderNo }}</td>
              <td class="amount">¥{{ row.amount }}</td>
              <td>{{ row.reason }}</td>
              <td>
                <span class="tag" :class="row.status === 'REFUNDED' ? 'success' : row.status === 'REJECTED' ? 'danger' : 'warning'">
                  {{ row.status }}
                </span>
              </td>
              <td style="text-align: right;">
                <template v-if="row.status === 'APPLYING'">
                  <button type="button" class="text-button text-success" @click="decision(row, 'APPROVE')">同意退款</button>
                  <span class="divider">|</span>
                  <button type="button" class="text-button text-danger" @click="decision(row, 'REJECT')">拒绝</button>
                </template>
                <span v-else class="muted-text">已处理完毕</span>
              </td>
            </tr>

            <tr v-else-if="resource === 'guides'">
              <td><strong>{{ row.name }}</strong></td>
              <td>{{ row.phone || '—' }}</td>
              <td>{{ row.intro || '—' }}</td>
              <td><span class="tag success">{{ row.status }}</span></td>
            </tr>

            <tr v-else>
              <td><strong>{{ row.name }}</strong></td>
              <td>{{ row.city || row.address || '—' }}</td>
              <td>{{ row.longitude || '—' }}, {{ row.latitude || '—' }}</td>
              <td>{{ row.dataSource || '—' }}</td>
              <td><span class="tag success">{{ row.status }}</span></td>
            </tr>
          </template>
        </tbody>
      </table>

      <div v-else class="empty-box">
        暂无相关资料数据。
      </div>
    </div>
  </div>
</template>

<style scoped>
.amount {
  color: var(--price-orange);
  font-weight: 800;
}

.divider {
  color: var(--border-strong);
  margin: 0 6px;
  font-size: 11px;
}

.text-success {
  color: var(--success-text) !important;
}

.text-danger {
  color: var(--danger-red) !important;
}

.muted-text {
  color: var(--text-tertiary);
  font-size: 12px;
}
</style>
