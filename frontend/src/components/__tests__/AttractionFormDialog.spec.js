// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ElMessage } from 'element-plus'
import AttractionFormDialog from '../AttractionFormDialog.vue'

/**
 * 景点新增/修改表单的契约测试。
 *
 * <p>覆盖的是旧实现留下的两个真实违约点：</p>
 * <ul>
 *   <li>请求体里的 {@code status} 必须是契约 {@code AccountStatus}（{@code ACTIVE}/{@code DISABLED}），
 *       不能是库内的 1/0 —— 早期后台直接以实体收发，前端拿到的还是整数状态；</li>
 *   <li>{@code longitude} / {@code latitude} 必须是 JSON number（或 null），不能是字符串，
 *       否则后端的 {@code Double} 绑定与契约 {@code Longitude} 都无从校验。</li>
 * </ul>
 */

const createAttraction = vi.fn()
const updateAttraction = vi.fn()

vi.mock('@/api/modules', () => ({
  adminApi: {
    createAttraction: (...args) => createAttraction(...args),
    updateAttraction: (...args) => updateAttraction(...args)
  }
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() }
}))

/** 已停用的景点：编辑时表单必须回填 DISABLED，保存不得把它悄悄改成启用。 */
const disabledAttraction = {
  id: '12',
  name: '苍山',
  city: '大理',
  address: '云南省大理市苍山',
  longitude: 100.1005,
  latitude: 25.6896,
  intro: '演示简介',
  dataSource: '团队测试数据',
  status: 'DISABLED',
  createdAt: '2026-09-01T10:00:00+08:00',
  updatedAt: '2026-09-02T10:00:00+08:00'
}

function mountDialog(attraction = null) {
  return mount(AttractionFormDialog, {
    props: { modelValue: true, attraction },
    global: {
      // el-dialog 由应用全局注册，测试里只保留插槽内容。
      stubs: { 'el-dialog': { template: '<div><slot /><slot name="footer" /></div>' } }
    }
  })
}

/**
 * 按表单项的 label 定位控件。
 *
 * <p>不能用占位符模糊匹配：名称的占位符是「例如：大理古城」、城市是「例如：大理」，
 * 前者包含后者，模糊匹配会先命中名称输入框（第一版测试就因此把城市留空、保存被校验拦下）。</p>
 */
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
  createAttraction.mockReset()
  updateAttraction.mockReset()
})

describe('AttractionFormDialog', () => {
  it('新增：提交契约字段，坐标是 number、可选字段为空时提交 null、状态默认 ACTIVE', async () => {
    createAttraction.mockResolvedValue({ id: '1', status: 'ACTIVE' })
    const wrapper = mountDialog()
    await flushPromises()

    await field(wrapper, '景点名称').setValue(' 西湖 ')
    await field(wrapper, '所属城市').setValue('杭州')
    await field(wrapper, '经度').setValue('120.13')
    await field(wrapper, '纬度').setValue('30.24')
    await field(wrapper, '数据来源说明').setValue('团队测试数据')
    await buttonByText(wrapper, '保存景点').trigger('click')
    await flushPromises()

    expect(createAttraction).toHaveBeenCalledTimes(1)
    const payload = createAttraction.mock.calls[0][0]
    expect(payload).toEqual({
      name: '西湖',
      city: '杭州',
      address: null,
      longitude: 120.13,
      latitude: 30.24,
      intro: null,
      dataSource: '团队测试数据',
      status: 'ACTIVE'
    })
    // 契约外字段一律不上送：id / createdAt / updatedAt 由后端与数据库决定。
    expect(payload).not.toHaveProperty('id')
    expect(payload).not.toHaveProperty('createdAt')
    expect(wrapper.emitted('saved')).toHaveLength(1)
    // 载荷要说明这次走的是 POST：调用方据此把列表刷到第 1 页（新建记录排在第一页）。
    expect(wrapper.emitted('saved')[0][0]).toEqual({ attraction: { id: '1', status: 'ACTIVE' }, created: true })
    expect(wrapper.emitted('update:modelValue').at(-1)).toEqual([false])
  })

  it('编辑已停用的景点：回填 DISABLED 并原样提交，不会静默改成启用', async () => {
    updateAttraction.mockResolvedValue({ ...disabledAttraction, name: '苍山（改名）' })
    const wrapper = mountDialog(disabledAttraction)
    await flushPromises()

    expect(wrapper.find('select').element.value).toBe('DISABLED')
    expect(field(wrapper, '经度').element.value).toBe('100.1005')

    await field(wrapper, '景点名称').setValue('苍山（改名）')
    await buttonByText(wrapper, '保存景点').trigger('click')
    await flushPromises()

    expect(updateAttraction).toHaveBeenCalledTimes(1)
    expect(updateAttraction.mock.calls[0][0]).toBe('12')
    expect(updateAttraction.mock.calls[0][1]).toMatchObject({
      name: '苍山（改名）',
      status: 'DISABLED',
      longitude: 100.1005,
      latitude: 25.6896
    })
    // 修改走的是 PUT：记录位置不变，调用方不该把列表刷回第 1 页。
    expect(wrapper.emitted('saved')[0][0]).toEqual({
      attraction: { ...disabledAttraction, name: '苍山（改名）' },
      created: false
    })
  })

  it('编辑：清空坐标与地址时提交 null，PUT 才能真正清空库内字段', async () => {
    updateAttraction.mockResolvedValue(disabledAttraction)
    const wrapper = mountDialog(disabledAttraction)
    await flushPromises()

    await field(wrapper, '经度').setValue('')
    await field(wrapper, '纬度').setValue('')
    await field(wrapper, '详细地址').setValue('')
    await buttonByText(wrapper, '保存景点').trigger('click')
    await flushPromises()

    const payload = updateAttraction.mock.calls[0][1]
    expect(payload.longitude).toBeNull()
    expect(payload.latitude).toBeNull()
    expect(payload.address).toBeNull()
  })

  it('页面校验：必填缺失与坐标超范围时不发请求', async () => {
    const wrapper = mountDialog()
    await flushPromises()

    await buttonByText(wrapper, '保存景点').trigger('click')
    await flushPromises()
    expect(createAttraction).not.toHaveBeenCalled()

    await field(wrapper, '景点名称').setValue('西湖')
    await field(wrapper, '所属城市').setValue('杭州')
    await field(wrapper, '数据来源说明').setValue('团队测试数据')
    await field(wrapper, '经度').setValue('181')
    await buttonByText(wrapper, '保存景点').trigger('click')
    await flushPromises()
    expect(createAttraction).not.toHaveBeenCalled()

    // 纬度范围是 ±90，经度合法值不能因为复用同一套校验被误拒。
    await field(wrapper, '经度').setValue('120.13')
    await field(wrapper, '纬度').setValue('91')
    await buttonByText(wrapper, '保存景点').trigger('click')
    await flushPromises()
    expect(createAttraction).not.toHaveBeenCalled()

    // 坐标全部合法后应当放行，避免上面的断言因为"永远不发请求"而假通过。
    await field(wrapper, '纬度').setValue('30.24')
    await buttonByText(wrapper, '保存景点').trigger('click')
    await flushPromises()
    expect(createAttraction).toHaveBeenCalledTimes(1)
  })

  it('后端校验失败时就地展示 message，且不关闭弹窗、不丢用户输入', async () => {
    createAttraction.mockRejectedValue(
      Object.assign(new Error('数据来源说明不能为空'), { status: 422, code: 'VALIDATION_ERROR' })
    )
    const wrapper = mountDialog()
    await flushPromises()

    await field(wrapper, '景点名称').setValue('西湖')
    await field(wrapper, '所属城市').setValue('杭州')
    await field(wrapper, '数据来源说明').setValue('团队测试数据')
    await buttonByText(wrapper, '保存景点').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('数据来源说明不能为空')
    expect(wrapper.emitted('saved')).toBeUndefined()
    expect(field(wrapper, '景点名称').element.value).toBe('西湖')
  })

  /**
   * 长度口径：契约的 maxLength 数的是字符（码点），HTML 的 maxlength 与 JS 的 String#length
   * 数的是 UTF-16 码元。128 个 emoji 的景点名在契约与库内 VARCHAR(128)（utf8mb4 按码点计）
   * 下都合法，码元却是 256 —— 既会被 maxlength 静默截断，也会被 `.length` 校验误判成超长。
   */
  it('名称按 Unicode 码点校验：128 个 emoji 合法放行，129 个被拦下且不发请求', async () => {
    createAttraction.mockResolvedValue({ id: '2', status: 'ACTIVE' })
    const wrapper = mountDialog()
    await flushPromises()

    await field(wrapper, '景点名称').setValue('😀'.repeat(128))
    await field(wrapper, '所属城市').setValue('杭州')
    await field(wrapper, '数据来源说明').setValue('团队测试数据')
    await buttonByText(wrapper, '保存景点').trigger('click')
    await flushPromises()

    expect(createAttraction).toHaveBeenCalledTimes(1)
    expect(createAttraction.mock.calls[0][0].name).toBe('😀'.repeat(128))

    createAttraction.mockClear()
    await field(wrapper, '景点名称').setValue('😀'.repeat(129))
    await buttonByText(wrapper, '保存景点').trigger('click')
    await flushPromises()

    expect(createAttraction).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalledWith('景点名称最多 128 个字符')
  })

  it('文本输入框不设 maxlength，计数器按码点显示当前长度', async () => {
    const wrapper = mountDialog()
    await flushPromises()

    for (const label of ['景点名称', '所属城市', '详细地址', '景点简介', '数据来源说明']) {
      expect(field(wrapper, label).attributes('maxlength')).toBeUndefined()
    }

    // 64 个 emoji 是 64 个码点（128 个码元）：计数器与校验都认 64。
    await field(wrapper, '景点名称').setValue('😀'.repeat(64))
    expect(wrapper.find('.form-counter').text()).toBe('64 / 128')
  })
})
