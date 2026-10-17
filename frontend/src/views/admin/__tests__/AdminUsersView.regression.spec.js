// @vitest-environment jsdom
// Additional failure, cancellation and response-order regressions alongside the wiring suite.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import AdminUsersView from '../AdminUsersView.vue'

const { api, confirm } = vi.hoisted(() => ({
  api: { users: vi.fn(), user: vi.fn(), staff: vi.fn(), updateUserStatus: vi.fn(), updateStaffStatus: vi.fn() }, confirm: vi.fn()
}))
vi.mock('@/api/modules', () => ({ adminApi: api }))
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() }, ElMessageBox: { confirm } }))
const staff = { id: '7', userId: '20', username: 'staff_demo', realName: '王顾问', employeeNo: 'E001', phone: '13800138000', department: '运营部', position: '顾问', status: 'ACTIVE' }
const page = (items = [staff], totalPages = 1) => ({ items, total: items.length, totalPages })
const wrappers = []
function view(mode = 'staff') {
  const w = mount(AdminUsersView, { props: { mode }, global: { stubs: {
    StaffFormDialog: true, 'el-skeleton': true,
    'el-dialog': { props: ['modelValue'], template: '<div v-if="modelValue"><slot/></div>' }
  } } }); wrappers.push(w); return w
}
function button(w, text) { return w.findAll('button').find(b => b.text() === text) }
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
beforeEach(() => {
  vi.resetAllMocks(); api.staff.mockResolvedValue(page()); api.users.mockResolvedValue(page([{ ...staff, id: '20', nickname: '游客' }])); api.user.mockResolvedValue({ id: '20', username: 'staff_demo', roles: ['USER'] }); confirm.mockResolvedValue('confirm')
})
afterEach(() => wrappers.splice(0).forEach(w => w.unmount()))
describe('用户与工作人员管理', () => {
  it('两种模式实际挂载，既有用户列表和详情仍可使用', async () => {
    const w = view('users'); await flushPromises()
    expect(api.users).toHaveBeenCalledWith({ page: 1, size: 20, keyword: undefined, status: undefined })
    expect(w.text()).toContain('游客'); await button(w, '查看').trigger('click'); await flushPromises()
    expect(w.get('.user-detail').text()).toContain('staff_demo')
  })
  it('员工完整展示，新增和编辑接入表单；保存后刷新', async () => {
    const w = view(); await flushPromises()
    for (const text of ['王顾问', 'staff_demo', 'E001', '运营部', '顾问', '13800138000', '在岗']) expect(w.text()).toContain(text)
    await button(w, '+ 新增员工').trigger('click')
    const dialog = w.findComponent({ name: 'StaffFormDialog' })
    expect(dialog.props('modelValue')).toBe(true); expect(dialog.props('staff')).toBeNull()
    await button(w, '编辑').trigger('click'); expect(dialog.props('staff').id).toBe('7')
    dialog.vm.$emit('saved', { created: false }); await flushPromises(); expect(api.staff).toHaveBeenCalledTimes(2)
    dialog.vm.$emit('saved', { created: true }); await flushPromises(); expect(api.staff.mock.calls.at(-1)[0].page).toBe(1)
  })
  it('搜索状态与分页参数正确，重新筛选回第一页', async () => {
    api.staff.mockResolvedValue(page([staff], 3)); const w = view(); await flushPromises()
    await button(w, '下一页').trigger('click'); await flushPromises(); expect(api.staff.mock.calls.at(-1)[0].page).toBe(2)
    await w.get('input.search-input').setValue(' 王 '); await w.get('select').setValue('DISABLED'); await w.get('form').trigger('submit'); await flushPromises()
    expect(api.staff.mock.calls.at(-1)[0]).toEqual({ page: 1, size: 20, keyword: '王', status: 'DISABLED' })
  })
  it.each(['ACTIVE', 'DISABLED'])('启停均先确认且使用 staff ID：%s', async status => {
    api.staff.mockResolvedValue(page([{ ...staff, status }])); const w = view(); await flushPromises()
    await button(w, status === 'ACTIVE' ? '停用' : '恢复').trigger('click'); await flushPromises()
    expect(confirm).toHaveBeenCalledTimes(1); expect(api.updateStaffStatus).toHaveBeenCalledWith('7', status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE')
    expect(api.staff).toHaveBeenCalledTimes(2)
  })
  it('取消不请求，失败保留状态，提交期间防重复', async () => {
    const w = view(); await flushPromises(); confirm.mockRejectedValueOnce('cancel')
    await button(w, '停用').trigger('click'); await flushPromises(); expect(api.updateStaffStatus).not.toHaveBeenCalled()
    const pending = deferred(); api.updateStaffStatus.mockReturnValueOnce(pending.promise)
    await button(w, '停用').trigger('click'); await flushPromises()
    expect(button(w, '提交中…').element.disabled).toBe(true)
    pending.reject(new Error('失败')); await flushPromises()
    expect(w.text()).toContain('在岗'); expect(button(w, '停用').element.disabled).toBe(false)
  })
  it('空状态与错误分开，失败可重试', async () => {
    api.staff.mockRejectedValueOnce(new Error('连接失败')).mockResolvedValueOnce(page([]))
    const w = view(); await flushPromises(); expect(w.get('[role="alert"]').text()).toContain('连接失败')
    await button(w, '重新加载').trigger('click'); await flushPromises(); expect(w.text()).toContain('暂无符合条件的员工')
    expect(w.find('[role="alert"]').exists()).toBe(false)
  })
  it('旧成功和旧失败均不能覆盖新筛选或提前结束加载', async () => {
    const a = deferred(), b = deferred(), c = deferred()
    api.staff.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise).mockReturnValueOnce(c.promise)
    const w = view(); await w.get('form').trigger('submit')
    a.resolve(page([{ ...staff, realName: '旧数据' }])); await flushPromises(); expect(w.find('[role="status"]').exists()).toBe(true)
    await w.get('form').trigger('submit'); c.resolve(page([{ ...staff, realName: '最新数据' }])); await flushPromises()
    b.reject(new Error('旧请求失败')); await flushPromises()
    expect(w.text()).toContain('最新数据'); expect(w.text()).not.toContain('旧请求失败')
  })
})
