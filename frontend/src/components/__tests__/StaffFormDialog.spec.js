// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import StaffFormDialog from '../StaffFormDialog.vue'

const { createStaff, updateStaff } = vi.hoisted(() => ({ createStaff: vi.fn(), updateStaff: vi.fn() }))
vi.mock('@/api/modules', () => ({ adminApi: { createStaff, updateStaff } }))
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn(), warning: vi.fn() } }))
const wrappers = []
const DialogStub = { props: ['closeOnPressEscape'], template: '<div><slot/><slot name="footer"/></div>' }
const staff = { id: '7', userId: '20', username: 'staff_demo', realName: '王顾问', employeeNo: 'E001', phone: '13800138000', department: '运营部', position: '顾问' }
function dialog(record = null) {
  const wrapper = mount(StaffFormDialog, {
    props: { modelValue: true, staff: record },
    global: { stubs: { 'el-dialog': DialogStub } }
  })
  wrappers.push(wrapper)
  return wrapper
}
function field(w, label) {
  return w.findAll('.form-field').find(g => g.get('label').text().startsWith(label)).get('input')
}
function save(w) { return w.findAll('button').find(b => b.text().includes('保存')).trigger('click') }
async function fill(w) {
  for (const [label, value] of [['登录账号', 'staff_demo'], ['初始密码', 'DemoPass123!'], ['真实姓名', '王顾问'], ['员工工号', 'E001']]) await field(w, label).setValue(value)
}
beforeEach(() => { vi.resetAllMocks(); createStaff.mockResolvedValue(staff); updateStaff.mockResolvedValue(staff) })
afterEach(() => wrappers.splice(0).forEach(w => w.unmount()))

describe('工作人员表单', () => {
  it('创建仅提交固定账号类型允许的字段，成功通知列表刷新', async () => {
    const w = dialog(); await fill(w); await save(w); await flushPromises()
    expect(createStaff).toHaveBeenCalledWith({ username: 'staff_demo', password: 'DemoPass123!', realName: '王顾问', employeeNo: 'E001', phone: null, department: null, position: null })
    expect(w.find('select').exists()).toBe(false)
    expect(w.emitted('saved')[0][0]).toEqual({ staff, created: true })
  })
  it('编辑用工作人员 ID，不提交用户名、绑定账号、密码、状态或角色', async () => {
    const w = dialog(staff); await field(w, '真实姓名').setValue('李顾问'); await save(w); await flushPromises()
    expect(updateStaff).toHaveBeenCalledWith('7', { realName: '李顾问', employeeNo: 'E001', phone: '13800138000', department: '运营部', position: '顾问' })
    expect(field(w, '登录账号').attributes('disabled')).toBeDefined()
    expect(w.emitted('saved')[0][0].created).toBe(false)
  })
  it.each(['员工工号已存在', '用户名已存在', '字段校验失败'])('错误可见并允许原表单重试：%s', async message => {
    createStaff.mockRejectedValueOnce(new Error(message))
    const w = dialog(); await fill(w); await save(w); await flushPromises()
    expect(w.get('[role="alert"]').text()).toContain(message)
    expect(field(w, '员工工号').element.value).toBe('E001')
    await save(w); await flushPromises(); expect(createStaff).toHaveBeenCalledTimes(2)
  })
  it('校验必填、账号格式及 Unicode 密码字节上限', async () => {
    const w = dialog(); await save(w); expect(createStaff).not.toHaveBeenCalled()
    await fill(w); await field(w, '登录账号').setValue('bad name'); await save(w); expect(createStaff).not.toHaveBeenCalled()
    await field(w, '登录账号').setValue('staff_demo'); await field(w, '初始密码').setValue('汉'.repeat(25)); await save(w); expect(createStaff).not.toHaveBeenCalled()
    await field(w, '初始密码').setValue('汉'.repeat(24)); await save(w); await flushPromises(); expect(createStaff).toHaveBeenCalledTimes(1)
  })
  it('提交期间不能重复保存、取消或按 Escape 关闭', async () => {
    let resolve
    createStaff.mockImplementation(() => new Promise(r => { resolve = r }))
    const w = dialog(); await fill(w); await save(w); await save(w)
    expect(createStaff).toHaveBeenCalledTimes(1)
    expect(w.findAll('button').every(b => b.element.disabled)).toBe(true)
    expect(w.findComponent(DialogStub).props('closeOnPressEscape')).toBe(false)
    resolve(staff); await flushPromises()
  })
  it('取消不提交', async () => {
    const w = dialog(); await w.findAll('button').find(b => b.text() === '取消').trigger('click')
    expect(createStaff).not.toHaveBeenCalled(); expect(w.emitted('update:modelValue')[0]).toEqual([false])
  })
})
