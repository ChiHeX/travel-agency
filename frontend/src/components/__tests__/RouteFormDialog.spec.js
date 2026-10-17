// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ElMessage } from 'element-plus'
import RouteFormDialog from '../RouteFormDialog.vue'

/**
 * 线路基本资料表单的测试。
 *
 * <p>这里钉住两件事：</p>
 * <ul>
 *   <li>请求体只含契约 {@code RouteUpsertRequest} 允许的字段，可选字段留空提交 {@code null}
 *       （PUT 才能真的清空历史内容）；</li>
 *   <li>文本长度按 <b>Unicode 码点</b> 校验：契约的 {@code maxLength} 与后端
 *       {@code @CodePointLength} 数码点，而 HTML 的 {@code maxlength} 与 {@code String#length}
 *       数 UTF-16 码元 —— 一个 emoji 是 1 个码点却是 2 个码元，用码元口径既会静默截断
 *       契约允许的内容，也会把合法的 emoji 文本误判成超长。</li>
 * </ul>
 */

const createRoute = vi.fn()
const updateRoute = vi.fn()

vi.mock('@/api/modules', () => ({
  adminApi: {
    createRoute: (...args) => createRoute(...args),
    updateRoute: (...args) => updateRoute(...args)
  }
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() }
}))

const publishedRoute = {
  id: '7',
  name: '昆明·大理·丽江 6 日跟团游',
  departureCity: '上海',
  destination: '云南',
  durationDays: 6,
  description: '经典线路',
  coverUrl: 'https://example.com/cover.png',
  included: '交通、住宿',
  excluded: '个人消费',
  bookingNotice: '带好身份证',
  status: 'PUBLISHED'
}

function mountDialog(route = null) {
  return mount(RouteFormDialog, {
    props: { modelValue: true, route },
    global: {
      // el-dialog 由应用全局注册，测试里只保留插槽内容。
      stubs: { 'el-dialog': { template: '<div><slot /><slot name="footer" /></div>' } }
    }
  })
}

/** 按表单项的 label 定位控件：名称/简介、城市/目的地等标签前缀互相包含，必须整段匹配。 */
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

/** 填好三个必填字段，让用例只关注被断言的那一项。 */
async function fillRequired(wrapper, { name = '云南线路', departureCity = '上海', destination = '云南' } = {}) {
  await field(wrapper, '线路名称').setValue(name)
  await field(wrapper, '出发城市').setValue(departureCity)
  await field(wrapper, '目的地').setValue(destination)
}

beforeEach(() => {
  createRoute.mockReset()
  updateRoute.mockReset()
  ElMessage.warning.mockReset()
})

describe('RouteFormDialog', () => {
  it('新增：提交契约字段，可选字段留空提交 null，天数按数字提交', async () => {
    createRoute.mockResolvedValue({ id: '1' })
    const wrapper = mountDialog()
    await flushPromises()

    await fillRequired(wrapper, { name: ' 云南线路 ' })
    await field(wrapper, '行程天数').setValue('6')
    await buttonByText(wrapper, '保存线路').trigger('click')
    await flushPromises()

    expect(createRoute).toHaveBeenCalledTimes(1)
    expect(createRoute.mock.calls[0][0]).toEqual({
      name: '云南线路',
      departureCity: '上海',
      destination: '云南',
      durationDays: 6,
      description: null,
      coverUrl: null,
      included: null,
      excluded: null,
      bookingNotice: null
    })
    // 状态、评分、报名人次都由后端决定，前端不得上送。
    expect(createRoute.mock.calls[0][0]).not.toHaveProperty('status')
  })

  it('编辑：清空可选字段时提交 null，PUT 才能真正清空库内内容', async () => {
    updateRoute.mockResolvedValue(publishedRoute)
    const wrapper = mountDialog(publishedRoute)
    await flushPromises()

    expect(field(wrapper, '线路简介').element.value).toBe('经典线路')

    await field(wrapper, '线路简介').setValue('')
    await field(wrapper, '费用包含').setValue('')
    await buttonByText(wrapper, '保存线路').trigger('click')
    await flushPromises()

    expect(updateRoute.mock.calls[0][0]).toBe('7')
    expect(updateRoute.mock.calls[0][1]).toMatchObject({ description: null, included: null })
  })

  /**
   * 线路名称契约是 2-200 个字符：200 个 emoji 是 200 个码点（400 个码元），
   * 契约允许、库内 VARCHAR(200) 也存得下，页面必须放行；201 个码点才该被拦下。
   */
  it('线路名称按码点校验：200 个 emoji 放行，201 个被拦下且不发请求', async () => {
    createRoute.mockResolvedValue({ id: '2' })
    const wrapper = mountDialog()
    await flushPromises()

    await fillRequired(wrapper, { name: '😀'.repeat(200) })
    await buttonByText(wrapper, '保存线路').trigger('click')
    await flushPromises()
    expect(createRoute).toHaveBeenCalledTimes(1)
    expect(createRoute.mock.calls[0][0].name).toBe('😀'.repeat(200))

    createRoute.mockClear()
    await fillRequired(wrapper, { name: '😀'.repeat(201) })
    await buttonByText(wrapper, '保存线路').trigger('click')
    await flushPromises()

    expect(createRoute).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalledWith('线路名称长度应为 2-200 个字符')
  })

  it('线路名称下限按码点算：1 个 emoji 不足 2 个字符，被拦下', async () => {
    const wrapper = mountDialog()
    await flushPromises()

    await fillRequired(wrapper, { name: '😀' })
    await buttonByText(wrapper, '保存线路').trigger('click')
    await flushPromises()

    expect(createRoute).not.toHaveBeenCalled()
  })

  /**
   * 五个长文本字段此前只有 maxlength、没有任何校验：maxlength 按码元截断，既不提示也不上报。
   * 去掉截断后必须自己拦，否则超长内容只会在提交时收到一个 422。
   */
  it('长文本字段超限时在提交前拦下，不把超长内容发给后端', async () => {
    const wrapper = mountDialog()
    await flushPromises()

    await fillRequired(wrapper)
    await field(wrapper, '线路简介').setValue('a'.repeat(10001))
    await buttonByText(wrapper, '保存线路').trigger('click')
    await flushPromises()

    expect(createRoute).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalledWith('线路简介不能超过 10000 个字符')

    await field(wrapper, '线路简介').setValue('a'.repeat(10000))
    await field(wrapper, '报名须知').setValue('b'.repeat(10001))
    await buttonByText(wrapper, '保存线路').trigger('click')
    await flushPromises()

    expect(createRoute).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalledWith('报名须知不能超过 10000 个字符')
  })

  it('文本输入框不设 maxlength，计数器按码点显示当前长度', async () => {
    const wrapper = mountDialog()
    await flushPromises()

    for (const label of ['线路名称', '出发城市', '目的地', '封面图地址', '线路简介', '费用包含', '费用不含', '报名须知']) {
      expect(field(wrapper, label).attributes('maxlength')).toBeUndefined()
    }

    await field(wrapper, '线路名称').setValue('😀'.repeat(100))
    expect(wrapper.find('.form-counter').text()).toBe('100 / 200')
  })

  it('后端拒绝时就地展示 message，不关闭弹窗、不丢用户输入', async () => {
    createRoute.mockRejectedValue(Object.assign(new Error('线路名称已存在'), { status: 409 }))
    const wrapper = mountDialog()
    await flushPromises()

    await fillRequired(wrapper)
    await buttonByText(wrapper, '保存线路').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('线路名称已存在')
    expect(wrapper.emitted('saved')).toBeUndefined()
    expect(field(wrapper, '线路名称').element.value).toBe('云南线路')
  })
})
