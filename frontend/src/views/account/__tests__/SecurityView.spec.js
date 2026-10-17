// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import SecurityView from '../SecurityView.vue'

/**
 * 改密页的口令规则（契约 API.md §4.2、PasswordChangeRequest）。
 *
 * <p>两类字段的口径必须不同，这是本文件要钉住的核心：</p>
 * <ul>
 *   <li><b>新密码（设置类）</b>：8–72 个字符（Unicode 码点）+ UTF-8 不超过 72 字节；</li>
 *   <li><b>原密码（验证类）</b>：只要非空 + 不超过 72 字节，<b>不套用 8 字符下限</b>。
 *       历史口令是按 UTF-16 码元口径创建并保存的（`😀😀😀😀` 只有 4 个字符却有 8 个码元），
 *       前端若按「不足 8 个字符」拦下，这些账号就再也改不了密码 —— 而契约不提供管理员重置入口，
 *       等于把旧账号锁死在旧口令上。</li>
 * </ul>
 */

const changePassword = vi.fn()
const logout = vi.fn()
const replace = vi.fn()

vi.mock('@/api/modules', () => ({
  authApi: { changePassword: (...args) => changePassword(...args) }
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ logout })
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ replace })
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() }
}))

/** 旧版本创建并保存下来的口令：4 个码点 / 8 个 UTF-16 码元 / 16 字节。 */
const LEGACY_PASSWORD = '😀😀😀😀'

function mountView() {
  return mount(SecurityView, { attachTo: document.body })
}

function input(wrapper, id) {
  const element = wrapper.find(`#${id}`)
  if (!element.exists()) throw new Error(`找不到输入框 #${id}`)
  return element
}

async function fill(wrapper, { current = 'DemoPass123!', next = 'Changed456!', confirmation } = {}) {
  await input(wrapper, 'current-password').setValue(current)
  await input(wrapper, 'new-password').setValue(next)
  await input(wrapper, 'confirm-password').setValue(confirmation === undefined ? next : confirmation)
}

async function submit(wrapper) {
  await wrapper.find('form').trigger('submit')
  await flushPromises()
}

beforeEach(() => {
  changePassword.mockReset().mockResolvedValue(undefined)
  logout.mockReset()
  replace.mockReset()
})

describe('SecurityView（改密口令规则）', () => {
  it('旧版本的短口令可以作为原密码提交：前端不按 8 字符拦下，原样交给服务端做哈希匹配', async () => {
    const wrapper = mountView()
    expect(LEGACY_PASSWORD.length).toBe(8)
    expect([...LEGACY_PASSWORD].length).toBe(4)

    await fill(wrapper, { current: LEGACY_PASSWORD })
    await submit(wrapper)

    expect(changePassword).toHaveBeenCalledTimes(1)
    expect(changePassword).toHaveBeenCalledWith({
      currentPassword: LEGACY_PASSWORD,
      newPassword: 'Changed456!'
    })
    expect(logout).toHaveBeenCalled()
    expect(wrapper.find('.form-error').exists()).toBe(false)
  })

  it('原密码为空时不发请求', async () => {
    const wrapper = mountView()
    await fill(wrapper, { current: '' })
    await submit(wrapper)

    expect(changePassword).not.toHaveBeenCalled()
    expect(wrapper.find('.form-error').text()).toBe('请输入原密码')
  })

  it('原密码只受字节上限约束：73 个字节被拦下，并说明是字节口径', async () => {
    const wrapper = mountView()
    await fill(wrapper, { current: 'a'.repeat(73) })
    await submit(wrapper)

    expect(changePassword).not.toHaveBeenCalled()
    expect(wrapper.find('.form-error').text()).toContain('原密码过长')
  })

  it('新密码仍严格要求 8 个码点：4 个 emoji 被拦下（码元口径会误判为 8）', async () => {
    const wrapper = mountView()
    await fill(wrapper, { next: LEGACY_PASSWORD })
    await submit(wrapper)

    expect(changePassword).not.toHaveBeenCalled()
    expect(wrapper.find('.form-error').text()).toBe('新密码至少 8 位')
  })

  it('新密码边界：8 个 emoji（8 码点 / 32 字节）放行，25 个汉字（25 码点 / 75 字节）按字节上限拦下', async () => {
    const wrapper = mountView()
    await fill(wrapper, { next: '😀'.repeat(8) })
    await submit(wrapper)
    expect(changePassword).toHaveBeenCalledTimes(1)

    changePassword.mockClear()
    await fill(wrapper, { next: '汉'.repeat(25) })
    await submit(wrapper)
    expect(changePassword).not.toHaveBeenCalled()
    expect(wrapper.find('.form-error').text()).toContain('新密码过长')
  })

  it('两个密码框都不设按码元截断的 maxlength，只有新密码保留 minlength="8"', async () => {
    const wrapper = mountView()

    for (const id of ['current-password', 'new-password', 'confirm-password']) {
      expect(input(wrapper, id).attributes('maxlength')).toBeUndefined()
    }
    // 原密码不设 minlength：它就是被旧口径创建的历史口令的入口
    expect(input(wrapper, 'current-password').attributes('minlength')).toBeUndefined()
    expect(input(wrapper, 'new-password').attributes('minlength')).toBe('8')
  })
})
