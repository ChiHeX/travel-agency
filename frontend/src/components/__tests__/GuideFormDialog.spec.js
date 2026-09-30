// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import GuideFormDialog from '../GuideFormDialog.vue'

/**
 * 导游新增 / 修改表单的契约测试（契约 {@code GuideCreateRequest} / {@code GuideUpdateRequest} / {@code Guide}）。
 *
 * <p>覆盖导游这一档最容易出现的违约点：</p>
 * <ul>
 *   <li>新增走 {@code GuideCreateRequest}，请求体只含 {@code username / password / name / phone / intro}，
 *       不能夹带 {@code id}、{@code userId} 或 {@code status}（严格模式下就是 400）；</li>
 *   <li>修改走 {@code GuideUpdateRequest}，只提交 {@code name / phone / intro}：
 *       账号名不在修改范围内，密码也不由该端点修改（那是导游本人的账号安全流程）；</li>
 *   <li>启用 / 停用是独立的 {@code PATCH /admin/guides/{id}/status}，不能混进资料表单 ——
 *       否则"编辑一下资料"就会顺带改写账号状态；</li>
 *   <li>密码上限有两个口径：契约 72 个字符、BCrypt 72 个 UTF-8 字节，缺一不可
 *       （25 个汉字只有 25 个字符却占 75 字节，只校验字符数会让后端 422 / 500）；</li>
 *   <li>文本长度按 Unicode 码点计数，不能用 JS 的 UTF-16 码元（emoji 会被误判成超长）。</li>
 * </ul>
 */

const createGuide = vi.fn()
const updateGuide = vi.fn()

vi.mock('@/api/modules', () => ({
  adminApi: {
    createGuide: (...args) => createGuide(...args),
    updateGuide: (...args) => updateGuide(...args)
  }
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() }
}))

/** 契约 Guide 的形状：含 username，状态是 AccountStatus。 */
const activeGuide = {
  id: '7',
  userId: '3',
  username: 'guide_lee',
  name: '李导',
  phone: '13800138001',
  intro: '具有云南线路带团经验。',
  status: 'ACTIVE',
  createdAt: '2026-09-01T10:00:00+08:00',
  updatedAt: '2026-09-01T10:00:00+08:00'
}

function mountDialog(guide = null) {
  return mount(GuideFormDialog, {
    props: { modelValue: true, guide },
    global: {
      // el-dialog 由应用全局注册，测试里只保留插槽内容。
      stubs: { 'el-dialog': { template: '<div><slot /><slot name="footer" /></div>' } }
    }
  })
}

/** 按表单项的 label 定位控件；不能用占位符模糊匹配。 */
function field(wrapper, label) {
  const group = wrapper.findAll('.form-field')
    .find((item) => item.find('label').exists() && item.find('label').text().startsWith(label))
  if (!group) throw new Error(`找不到标签以「${label}」开头的表单项`)
  const control = group.find('input, textarea, select')
  if (!control.exists()) throw new Error(`表单项「${label}」里没有输入控件`)
  return control
}

function buttonByText(wrapper, text) {
  const button = wrapper.findAll('button').find((item) => item.text().includes(text))
  if (!button) {
    throw new Error(`找不到按钮「${text}」，现有按钮：${wrapper.findAll('button').map((b) => b.text()).join(' / ')}`)
  }
  return button
}

beforeEach(() => {
  createGuide.mockReset()
  updateGuide.mockReset()
})

describe('GuideFormDialog（新增导游）', () => {
  it('新增：提交契约字段，可选简介为空时提交 null，且不带 id/userId/status', async () => {
    createGuide.mockResolvedValue({ ...activeGuide, id: '8' })
    const wrapper = mountDialog()
    await flushPromises()

    await field(wrapper, '登录账号').setValue(' guide_lee ')
    await field(wrapper, '初始密码').setValue('guidePass123')
    await field(wrapper, '导游姓名').setValue(' 李导 ')
    await field(wrapper, '联系电话').setValue('13800138001')
    await buttonByText(wrapper, '保存导游').trigger('click')
    await flushPromises()

    expect(createGuide).toHaveBeenCalledTimes(1)
    expect(createGuide.mock.calls[0][0]).toEqual({
      username: 'guide_lee',
      password: 'guidePass123',
      name: '李导',
      phone: '13800138001',
      intro: null
    })
    // 契约外字段一律不上送：主键与账号关联由后端生成，状态走独立的 status 端点。
    const payload = createGuide.mock.calls[0][0]
    expect(payload).not.toHaveProperty('id')
    expect(payload).not.toHaveProperty('userId')
    expect(payload).not.toHaveProperty('status')
    expect(payload).not.toHaveProperty('createdAt')

    expect(wrapper.emitted('saved')).toHaveLength(1)
    // 载荷要说明这次走的是 POST：调用方据此把列表刷到第 1 页（新建记录排在第一页）。
    expect(wrapper.emitted('saved')[0][0]).toEqual({
      guide: { ...activeGuide, id: '8' },
      created: true
    })
    expect(wrapper.emitted('update:modelValue').at(-1)).toEqual([false])
  })

  it('新增：非空简介原样提交', async () => {
    createGuide.mockResolvedValue({ ...activeGuide, id: '8' })
    const wrapper = mountDialog()
    await flushPromises()

    await field(wrapper, '登录账号').setValue('guide_lee')
    await field(wrapper, '初始密码').setValue('guidePass123')
    await field(wrapper, '导游姓名').setValue('李导')
    await field(wrapper, '联系电话').setValue('13800138001')
    await field(wrapper, '个人简介').setValue('  十年带团经验  ')
    await buttonByText(wrapper, '保存导游').trigger('click')
    await flushPromises()

    expect(createGuide.mock.calls[0][0].intro).toBe('十年带团经验')
  })

  it('页面校验：账号名必须匹配契约正则，非法账号不发请求', async () => {
    createGuide.mockResolvedValue({ id: '8' })
    const wrapper = mountDialog()
    await flushPromises()

    const fill = async (username) => {
      await field(wrapper, '登录账号').setValue(username)
      await field(wrapper, '初始密码').setValue('guidePass123')
      await field(wrapper, '导游姓名').setValue('李导')
      await field(wrapper, '联系电话').setValue('13800138001')
      await buttonByText(wrapper, '保存导游').trigger('click')
      await flushPromises()
    }

    await fill('李白')            // 含中文
    expect(createGuide).not.toHaveBeenCalled()
    await fill('ab')              // 少于 3 位
    expect(createGuide).not.toHaveBeenCalled()
    await fill('guide-name')      // 含连字符
    expect(createGuide).not.toHaveBeenCalled()
    await fill('guide_lee1')      // 合法
    expect(createGuide).toHaveBeenCalledTimes(1)
  })

  it('页面校验：密码字符数与 UTF-8 字节数两个上限都要拦（25 个汉字 75 字节）', async () => {
    createGuide.mockResolvedValue({ id: '8' })
    const wrapper = mountDialog()
    await flushPromises()

    const fill = async (password) => {
      await field(wrapper, '登录账号').setValue('guide_lee')
      await field(wrapper, '初始密码').setValue(password)
      await field(wrapper, '导游姓名').setValue('李导')
      await field(wrapper, '联系电话').setValue('13800138001')
      await buttonByText(wrapper, '保存导游').trigger('click')
      await flushPromises()
    }

    await fill('short')               // 少于 8 位
    expect(createGuide).not.toHaveBeenCalled()
    await fill('密'.repeat(25))       // 25 字符 / 75 字节：字符数没超，字节数超了
    expect(createGuide).not.toHaveBeenCalled()
    // 边界：24 个汉字恰好 72 字节，必须放行，别把"按字节算"做成"一律拒绝"
    await fill('密'.repeat(24))
    expect(createGuide).toHaveBeenCalledTimes(1)
  })

  it('页面校验：姓名与联系电话的必填与长度边界', async () => {
    createGuide.mockResolvedValue({ id: '8' })
    const wrapper = mountDialog()
    await flushPromises()

    const fill = async ({ name, phone }) => {
      await field(wrapper, '登录账号').setValue('guide_lee')
      await field(wrapper, '初始密码').setValue('guidePass123')
      await field(wrapper, '导游姓名').setValue(name)
      await field(wrapper, '联系电话').setValue(phone)
      await buttonByText(wrapper, '保存导游').trigger('click')
      await flushPromises()
    }

    await fill({ name: '', phone: '13800138001' })       // 姓名必填
    expect(createGuide).not.toHaveBeenCalled()
    await fill({ name: '李'.repeat(65), phone: '13800138001' }) // 姓名上限 64
    expect(createGuide).not.toHaveBeenCalled()
    await fill({ name: '李导', phone: '13' })            // 电话下限 3
    expect(createGuide).not.toHaveBeenCalled()
    await fill({ name: '李导', phone: '1'.repeat(21) })  // 电话上限 20
    expect(createGuide).not.toHaveBeenCalled()
    await fill({ name: '李'.repeat(64), phone: '1'.repeat(20) }) // 恰好到上限
    expect(createGuide).toHaveBeenCalledTimes(1)
  })

  /**
   * 长度上限按 Unicode 码点算（契约 maxLength 的口径，与后端 @CodePointLength 一致），
   * 不是 JS 的 UTF-16 码元：用 String#length 会把 64 个 emoji 的姓名算成 128 而误拦。
   */
  it('长度校验按码点计数：码元超限但码点合法的内容不会被误拦', async () => {
    createGuide.mockResolvedValue({ id: '8' })
    const wrapper = mountDialog()
    await flushPromises()

    const emojiName = '😀'.repeat(64) // 64 码点 / 128 码元
    expect(emojiName.length).toBeGreaterThan(64)

    await field(wrapper, '登录账号').setValue('guide_lee')
    await field(wrapper, '初始密码').setValue('guidePass123')
    await field(wrapper, '导游姓名').setValue(emojiName)
    await field(wrapper, '联系电话').setValue('13800138001')
    await buttonByText(wrapper, '保存导游').trigger('click')
    await flushPromises()

    expect(createGuide).toHaveBeenCalledTimes(1)
    expect(createGuide.mock.calls[0][0].name).toBe(emojiName)

    // 超出码点上限（65 > 64）仍然要拦下，别把"按码点算"做成"不校验"
    await field(wrapper, '导游姓名').setValue('😀'.repeat(65))
    await buttonByText(wrapper, '保存导游').trigger('click')
    await flushPromises()
    expect(createGuide).toHaveBeenCalledTimes(1)
  })

  it('后端校验失败时就地展示 message，且不关闭弹窗、不丢用户输入', async () => {
    createGuide.mockRejectedValue(
      Object.assign(new Error('账号已存在'), { status: 409, code: 'USERNAME_ALREADY_EXISTS' })
    )
    const wrapper = mountDialog()
    await flushPromises()

    await field(wrapper, '登录账号').setValue('guide_lee')
    await field(wrapper, '初始密码').setValue('guidePass123')
    await field(wrapper, '导游姓名').setValue('李导')
    await field(wrapper, '联系电话').setValue('13800138001')
    await buttonByText(wrapper, '保存导游').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('账号已存在')
    expect(wrapper.emitted('saved')).toBeUndefined()
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(field(wrapper, '登录账号').element.value).toBe('guide_lee')
  })
})

describe('GuideFormDialog（编辑导游）', () => {
  it('编辑：账号名只读展示、不提供密码项，只提交 name/phone/intro', async () => {
    updateGuide.mockResolvedValue({ ...activeGuide, name: '李导（改）' })
    const wrapper = mountDialog(activeGuide)
    await flushPromises()

    const account = field(wrapper, '登录账号')
    expect(account.element.value).toBe('guide_lee')
    expect(account.attributes('disabled')).toBeDefined()
    // 编辑态没有"初始密码"表单项：密码不由导游资料端点修改
    expect(wrapper.findAll('.form-field').some((item) => item.find('label').text().startsWith('初始密码'))).toBe(false)
    expect(field(wrapper, '导游姓名').element.value).toBe('李导')

    await field(wrapper, '导游姓名').setValue('李导（改）')
    await buttonByText(wrapper, '保存导游').trigger('click')
    await flushPromises()

    expect(updateGuide).toHaveBeenCalledTimes(1)
    expect(updateGuide.mock.calls[0][0]).toBe('7')
    const payload = updateGuide.mock.calls[0][1]
    expect(payload).toEqual({ name: '李导（改）', phone: '13800138001', intro: '具有云南线路带团经验。' })
    // 账号名、密码与状态都不在 GuideUpdateRequest 里：带上就是契约外字段（400）
    expect(payload).not.toHaveProperty('username')
    expect(payload).not.toHaveProperty('password')
    expect(payload).not.toHaveProperty('status')
    // 修改走的是 PUT：记录位置不变，调用方不该把列表刷回第 1 页。
    expect(wrapper.emitted('saved')[0][0]).toEqual({
      guide: { ...activeGuide, name: '李导（改）' },
      created: false
    })
  })

  it('编辑：清空简介时提交 null，PUT 才能真正清空库内字段', async () => {
    updateGuide.mockResolvedValue({ ...activeGuide, intro: null })
    const wrapper = mountDialog(activeGuide)
    await flushPromises()

    await field(wrapper, '个人简介').setValue('   ')
    await buttonByText(wrapper, '保存导游').trigger('click')
    await flushPromises()

    expect(updateGuide.mock.calls[0][1].intro).toBeNull()
  })

  it('编辑：电话长度越界时拦在本地，不发请求', async () => {
    updateGuide.mockResolvedValue(activeGuide)
    const wrapper = mountDialog(activeGuide)
    await flushPromises()

    await field(wrapper, '联系电话').setValue('13')  // 少于 3 位
    await buttonByText(wrapper, '保存导游').trigger('click')
    await flushPromises()
    expect(updateGuide).not.toHaveBeenCalled()

    await field(wrapper, '联系电话').setValue('13800138001')
    await buttonByText(wrapper, '保存导游').trigger('click')
    await flushPromises()
    expect(updateGuide).toHaveBeenCalledTimes(1)
  })
})
