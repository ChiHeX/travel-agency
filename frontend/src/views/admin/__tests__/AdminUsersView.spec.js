// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import AdminUsersView from '../AdminUsersView.vue'

/**
 * 用户 / 员工管理页（AdminUsersView.vue）的接线测试。
 *
 * <p>该页面按 `mode` prop 分派两种视图：`users`（注册游客）和 `staff`（内部员工）。
 * 两个视图共用列表壳、搜索框与分页控件，但调用不同的 adminApi 端点，表格列也不同。
 * 这里钉住的是：页面能挂载、员工 CRUD 能接线、启停有确认、搜索分页带对参数、
 * 请求乱序不会用旧响应覆盖新结果。</p>
 *
 * <p>之前该页面曾被报告"模板用到 isUserMode 等变量但 <script setup> 未声明"，
 * 页面一挂载就抛 `isUserMode is not defined`。本文件的第一、二条用例即钉住这类问题：
 * 两种模式分别 mount 一次并 flushPromises，只要有任何未声明引用就会被暴露。</p>
 */

const fetchUsers = vi.fn()
const fetchStaff = vi.fn()
const fetchUserDetail = vi.fn()
const updateUserStatus = vi.fn()
const updateStaffStatus = vi.fn()
const confirmMock = vi.fn()

vi.mock('@/api/modules', () => ({
  adminApi: {
    users: (...args) => fetchUsers(...args),
    user: (...args) => fetchUserDetail(...args),
    updateUserStatus: (...args) => updateUserStatus(...args),
    staff: (...args) => fetchStaff(...args),
    updateStaffStatus: (...args) => updateStaffStatus(...args)
  }
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() },
  ElMessageBox: { confirm: (...args) => confirmMock(...args) }
}))

const activeUser = {
  id: '5',
  username: 'traveler_lee',
  nickname: '李旅人',
  realName: '李四',
  phone: '13800138000',
  email: 'traveler@example.com',
  avatarUrl: null,
  roles: ['USER'],
  status: 'ACTIVE',
  createdAt: '2026-09-01T10:00:00+08:00'
}

const activeStaff = {
  id: '7',
  userId: '9',
  username: 'staff_wang',
  realName: '王顾问',
  phone: '13900139000',
  employeeNo: 'EMP007',
  department: '运营部',
  position: '行程顾问',
  status: 'ACTIVE',
  createdAt: '2026-09-01T10:00:00+08:00',
  updatedAt: '2026-09-01T10:00:00+08:00'
}

function mountUsers() {
  return mount(AdminUsersView, {
    props: { mode: 'users' },
    global: {
      stubs: { 'el-skeleton': true, StaffFormDialog: true }
    }
  })
}

function mountStaff() {
  return mount(AdminUsersView, {
    props: { mode: 'staff' },
    global: {
      stubs: { 'el-skeleton': true, StaffFormDialog: true }
    }
  })
}

function buttonByText(wrapper, text) {
  const button = wrapper.findAll('button').find((item) => item.text().includes(text))
  if (!button) {
    throw new Error(`找不到按钮「${text}」，现有按钮：${wrapper.findAll('button').map((b) => b.text()).join(' / ')}`)
  }
  return button
}

beforeEach(() => {
  fetchUsers.mockReset().mockResolvedValue({ items: [{ ...activeUser }], total: 1, totalPages: 1 })
  fetchStaff.mockReset().mockResolvedValue({ items: [{ ...activeStaff }], total: 1, totalPages: 1 })
  fetchUserDetail.mockReset().mockResolvedValue({ ...activeUser })
  updateUserStatus.mockReset().mockResolvedValue({ ...activeUser, status: 'DISABLED' })
  updateStaffStatus.mockReset().mockResolvedValue({ ...activeStaff, status: 'DISABLED' })
  confirmMock.mockReset().mockResolvedValue('confirm')
})

// ---------------------------------------------------------------------------
// 页面挂载：钉住"未声明变量"这类错误
// ---------------------------------------------------------------------------

describe('AdminUsersView —— 页面挂载', () => {
  it('用户模式：进入即按契约分页拉取用户列表，并显示中文化的枚举状态', async () => {
    const wrapper = mountUsers()
    await flushPromises()

    expect(fetchUsers).toHaveBeenCalledTimes(1)
    expect(fetchUsers).toHaveBeenCalledWith({ page: 1, size: 20 })
    expect(wrapper.text()).toContain('traveler_lee')
    expect(wrapper.text()).toContain('李旅人')
    expect(wrapper.text()).toContain('正常使用')
    expect(wrapper.text()).not.toContain('ACTIVE')
    expect(fetchStaff).not.toHaveBeenCalled()
  })

  it('员工模式：进入即按契约分页拉取员工列表，并显示姓名/工号/部门/岗位等员工独有列', async () => {
    const wrapper = mountStaff()
    await flushPromises()

    expect(fetchStaff).toHaveBeenCalledTimes(1)
    expect(fetchStaff).toHaveBeenCalledWith({ page: 1, size: 20 })
    expect(wrapper.text()).toContain('王顾问')
    expect(wrapper.text()).toContain('EMP007')
    expect(wrapper.text()).toContain('运营部')
    expect(wrapper.text()).toContain('行程顾问')
    expect(wrapper.text()).toContain('在岗')
    expect(fetchUsers).not.toHaveBeenCalled()
  })

  it('员工模式渲染「+ 新增员工」按钮；用户模式不渲染', async () => {
    const staff = mountStaff()
    await flushPromises()
    expect(buttonByText(staff, '新增员工').exists()).toBe(true)

    const users = mountUsers()
    await flushPromises()
    expect(users.findAll('button').some((b) => b.text().includes('新增员工'))).toBe(false)
  })
})

// ---------------------------------------------------------------------------
// 员工创建 / 编辑：以空行或当前行打开表单弹窗
// ---------------------------------------------------------------------------

describe('AdminUsersView —— 员工创建 / 编辑', () => {
  it('点「+ 新增员工」：以空行打开 StaffFormDialog（modelValue=true，staff=null）', async () => {
    const wrapper = mountStaff()
    await flushPromises()

    const dialog = () => wrapper.findComponent({ name: 'StaffFormDialog' })
    expect(dialog().props('modelValue')).toBe(false)

    await buttonByText(wrapper, '新增员工').trigger('click')
    await flushPromises()

    expect(dialog().props('modelValue')).toBe(true)
    expect(dialog().props('staff')).toBeNull()
  })

  it('点「编辑」：以当前行打开 StaffFormDialog（staff 是那一行）', async () => {
    const wrapper = mountStaff()
    await flushPromises()

    await buttonByText(wrapper, '编辑').trigger('click')
    await flushPromises()

    const dialog = wrapper.findComponent({ name: 'StaffFormDialog' })
    expect(dialog.props('modelValue')).toBe(true)
    expect(dialog.props('staff')).toMatchObject({ id: '7', username: 'staff_wang' })
  })

  it('员工弹窗 saved 事件后刷新列表；created=true 时回到第 1 页', async () => {
    fetchStaff.mockResolvedValue({ items: [{ ...activeStaff }], total: 45, totalPages: 3 })
    const wrapper = mountStaff()
    await flushPromises()

    await buttonByText(wrapper, '下一页').trigger('click')
    await flushPromises()
    expect(fetchStaff).toHaveBeenLastCalledWith({ page: 2, size: 20 })

    const dialog = wrapper.findComponent({ name: 'StaffFormDialog' })

    // 编辑保存：留在当前页
    dialog.vm.$emit('saved', { staff: activeStaff, created: false })
    await flushPromises()
    expect(fetchStaff).toHaveBeenLastCalledWith({ page: 2, size: 20 })

    // 新增保存：回到第 1 页（新记录排在第一页）
    dialog.vm.$emit('saved', { staff: activeStaff, created: true })
    await flushPromises()
    expect(fetchStaff).toHaveBeenLastCalledWith({ page: 1, size: 20 })
  })
})

// ---------------------------------------------------------------------------
// 员工 / 用户 启停：确认与取消
// ---------------------------------------------------------------------------

describe('AdminUsersView —— 启停操作', () => {
  it('员工「停用」：弹确认框；确认后调用契约端点并用新的筛选条件刷新列表', async () => {
    const wrapper = mountStaff()
    await flushPromises()

    await buttonByText(wrapper, '停用').trigger('click')
    await flushPromises()

    expect(confirmMock).toHaveBeenCalledTimes(1)
    expect(updateStaffStatus).toHaveBeenCalledWith('7', 'DISABLED')
    expect(fetchStaff).toHaveBeenCalledTimes(2)
  })

  it('员工「恢复」：同样弹确认框；取消时既不调用接口也不刷新列表', async () => {
    fetchStaff.mockResolvedValue({
      items: [{ ...activeStaff, status: 'DISABLED' }],
      total: 1,
      totalPages: 1
    })
    confirmMock.mockRejectedValue(new Error('cancel'))
    const wrapper = mountStaff()
    await flushPromises()

    await buttonByText(wrapper, '恢复').trigger('click')
    await flushPromises()

    // 组长特别要求的：恢复操作也要有确认框
    expect(confirmMock).toHaveBeenCalledTimes(1)
    // 取消后不发请求、不刷新列表
    expect(updateStaffStatus).not.toHaveBeenCalled()
    expect(fetchStaff).toHaveBeenCalledTimes(1)
  })

  it('用户「停用」与「恢复」：与员工一致，确认/取消都生效', async () => {
    const wrapper = mountUsers()
    await flushPromises()

    await buttonByText(wrapper, '停用').trigger('click')
    await flushPromises()
    expect(confirmMock).toHaveBeenCalledTimes(1)
    expect(updateUserStatus).toHaveBeenCalledWith('5', 'DISABLED')
    expect(fetchUsers).toHaveBeenCalledTimes(2)

    fetchUsers.mockResolvedValue({
      items: [{ ...activeUser, status: 'DISABLED' }],
      total: 1,
      totalPages: 1
    })
    confirmMock.mockReset().mockRejectedValue(new Error('cancel'))
    const wrapper2 = mountUsers()
    await flushPromises()
    await buttonByText(wrapper2, '恢复').trigger('click')
    await flushPromises()

    expect(updateUserStatus).toHaveBeenCalledTimes(1)
  })

  it('启停在途时按钮禁用，连点不会发出第二个请求', async () => {
    let rejectFirst
    updateStaffStatus
      .mockImplementationOnce(() => new Promise((_resolve, reject) => { rejectFirst = reject }))
      .mockRejectedValue(Object.assign(new Error('conflict'), { status: 409 }))

    const wrapper = mountStaff()
    await flushPromises()

    await buttonByText(wrapper, '停用').trigger('click')

    const toggling = buttonByText(wrapper, '提交中')
    expect(toggling.attributes('disabled')).toBeDefined()

    await toggling.trigger('click')
    expect(updateStaffStatus).toHaveBeenCalledTimes(1)

    rejectFirst(Object.assign(new Error('conflict'), { status: 409 }))
    await flushPromises()

    expect(buttonByText(wrapper, '停用').attributes('disabled')).toBeUndefined()
  })
})

// ---------------------------------------------------------------------------
// 搜索与分页
// ---------------------------------------------------------------------------

describe('AdminUsersView —— 搜索与分页', () => {
  it('用户模式：提交搜索把 keyword 交给契约参数并回到第 1 页', async () => {
    fetchUsers.mockResolvedValue({ items: [{ ...activeUser }], total: 45, totalPages: 3 })
    const wrapper = mountUsers()
    await flushPromises()

    await buttonByText(wrapper, '下一页').trigger('click')
    await flushPromises()
    expect(fetchUsers).toHaveBeenLastCalledWith({ page: 2, size: 20 })

    await wrapper.find('.search-input').setValue('旅人')
    await wrapper.find('form.toolbar').trigger('submit')
    await flushPromises()

    expect(fetchUsers).toHaveBeenLastCalledWith({ page: 1, size: 20, keyword: '旅人' })
    expect(wrapper.text()).toContain('第 1 / 3 页')
  })

  it('员工模式：提交搜索同样把 keyword 交给契约参数', async () => {
    fetchStaff.mockResolvedValue({ items: [{ ...activeStaff }], total: 45, totalPages: 3 })
    const wrapper = mountStaff()
    await flushPromises()

    await wrapper.find('.search-input').setValue('王顾问')
    await wrapper.find('form.toolbar').trigger('submit')
    await flushPromises()

    expect(fetchStaff).toHaveBeenLastCalledWith({ page: 1, size: 20, keyword: '王顾问' })
  })

  it('分页：翻页时按新的 page 重新请求，首/末页按钮分别禁用', async () => {
    fetchUsers.mockResolvedValue({ items: [{ ...activeUser }], total: 45, totalPages: 3 })
    const wrapper = mountUsers()
    await flushPromises()

    expect(buttonByText(wrapper, '上一页').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('第 1 / 3 页，共 45 条')

    await buttonByText(wrapper, '下一页').trigger('click')
    await flushPromises()
    expect(fetchUsers).toHaveBeenLastCalledWith({ page: 2, size: 20 })
    expect(wrapper.text()).toContain('第 2 / 3 页，共 45 条')

    await buttonByText(wrapper, '下一页').trigger('click')
    await flushPromises()
    expect(buttonByText(wrapper, '下一页').attributes('disabled')).toBeDefined()
  })

  it('切换用户 / 员工模式时，清空筛选并改用另一套接口', async () => {
    const wrapper = mountUsers()
    await flushPromises()

    await wrapper.find('.search-input').setValue('旅人')
    await wrapper.find('form.toolbar').trigger('submit')
    await flushPromises()
    expect(fetchUsers).toHaveBeenLastCalledWith({ page: 1, size: 20, keyword: '旅人' })

    await wrapper.setProps({ mode: 'staff' })
    await flushPromises()

    expect(fetchStaff).toHaveBeenLastCalledWith({ page: 1, size: 20 })
    expect(wrapper.find('.search-input').element.value).toBe('')
  })
})

// ---------------------------------------------------------------------------
// 请求乱序：旧响应不能覆盖新结果
// ---------------------------------------------------------------------------

describe('AdminUsersView —— 请求乱序', () => {
  it('快速切换筛选：第一次请求后返回也不能覆盖第二次请求的结果', async () => {
    let resolveFirst
    fetchUsers
      .mockImplementationOnce(() => new Promise((resolve) => { resolveFirst = resolve }))
      .mockResolvedValueOnce({
        items: [{ ...activeUser, username: 'latest_user' }],
        total: 1,
        totalPages: 1
      })

    const wrapper = mountUsers()
    await flushPromises()

    await wrapper.find('.search-input').setValue('旅人')
    await wrapper.find('form.toolbar').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('latest_user')

    resolveFirst({ items: [{ ...activeUser, username: 'stale_user' }], total: 1, totalPages: 1 })
    await flushPromises()

    expect(wrapper.text()).toContain('latest_user')
    expect(wrapper.text()).not.toContain('stale_user')
  })

  it('快速翻页：旧页响应晚到也不能覆盖新页数据', async () => {
    let resolvePage1
    fetchUsers
      .mockImplementationOnce(() => new Promise((resolve) => { resolvePage1 = resolve }))
      .mockResolvedValueOnce({
        items: [{ ...activeUser, username: 'user_on_page_2' }],
        total: 40,
        totalPages: 2
      })

    const wrapper = mountUsers()
    await flushPromises()

    expect(fetchUsers).toHaveBeenCalledTimes(1)

    resolvePage1({ items: [{ ...activeUser, username: 'user_on_page_1' }], total: 40, totalPages: 2 })
    await flushPromises()

    expect(wrapper.text()).toContain('user_on_page_1')
    expect(wrapper.text()).not.toContain('user_on_page_2')
  })
})