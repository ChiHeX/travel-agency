// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import HotelFormDialog from '../HotelFormDialog.vue'

/**
 * 酒店资料新增/修改表单的契约测试（契约 {@code HotelUpsertRequest} / {@code Hotel}）。
 *
 * <p>覆盖的是酒店这一档最容易出现的违约点：</p>
 * <ul>
 *   <li>请求体里的 {@code status} 必须是契约 {@code AccountStatus}（{@code ACTIVE}/{@code DISABLED}），
 *       不能是库内的 1/0；</li>
 *   <li>{@code longitude} / {@code latitude} 必须是 JSON number（或 null），不能是字符串；</li>
 *   <li>契约里酒店<b>没有 city</b>，表单不得凭空提交后端不认识的字段（严格模式下就是 400）；</li>
 *   <li>可空字段清空时要提交 {@code null}，PUT 才能真正把库内字段清掉。</li>
 * </ul>
 */

const createHotel = vi.fn()
const updateHotel = vi.fn()

vi.mock('@/api/modules', () => ({
  adminApi: {
    createHotel: (...args) => createHotel(...args),
    updateHotel: (...args) => updateHotel(...args)
  }
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() }
}))

/** 已停用的酒店：编辑时表单必须回填 DISABLED，保存不得把它悄悄改成启用。 */
const disabledHotel = {
  id: '31',
  name: '苍山脚下的演示酒店',
  address: '云南省大理市',
  contactPhone: '0872-1234567',
  longitude: 100.1005,
  latitude: 25.6896,
  intro: '演示简介',
  dataSource: '团队测试数据',
  status: 'DISABLED',
  createdAt: '2026-09-01T10:00:00+08:00',
  updatedAt: '2026-09-02T10:00:00+08:00'
}

function mountDialog(hotel = null) {
  return mount(HotelFormDialog, {
    props: { modelValue: true, hotel },
    global: {
      // el-dialog 由应用全局注册，测试里只保留插槽内容。
      stubs: { 'el-dialog': { template: '<div><slot /><slot name="footer" /></div>' } }
    }
  })
}

/** 按表单项的 label 定位控件；不能用占位符模糊匹配（名称与地址的占位符高度相似）。 */
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
  createHotel.mockReset()
  updateHotel.mockReset()
})

describe('HotelFormDialog', () => {
  it('新增：提交契约字段，坐标是 number、可选字段为空时提交 null、状态默认 ACTIVE', async () => {
    createHotel.mockResolvedValue({ id: '1', status: 'ACTIVE' })
    const wrapper = mountDialog()
    await flushPromises()

    await field(wrapper, '酒店名称').setValue(' 大理演示酒店 ')
    await field(wrapper, '联系电话').setValue('0872-1234567')
    await field(wrapper, '经度').setValue('100.165')
    await field(wrapper, '纬度').setValue('25.694')
    await field(wrapper, '数据来源说明').setValue('团队测试数据')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()

    expect(createHotel).toHaveBeenCalledTimes(1)
    const payload = createHotel.mock.calls[0][0]
    expect(payload).toEqual({
      name: '大理演示酒店',
      address: null,
      contactPhone: '0872-1234567',
      longitude: 100.165,
      latitude: 25.694,
      intro: null,
      dataSource: '团队测试数据',
      status: 'ACTIVE'
    })
    // 契约外字段一律不上送：契约 Hotel 没有 city，id / createdAt / updatedAt 由后端与数据库决定。
    expect(payload).not.toHaveProperty('city')
    expect(payload).not.toHaveProperty('id')
    expect(payload).not.toHaveProperty('createdAt')
    expect(wrapper.emitted('saved')).toHaveLength(1)
    // 载荷要说明这次走的是 POST：调用方据此把列表刷到第 1 页（新建记录排在第一页）。
    expect(wrapper.emitted('saved')[0][0]).toEqual({ hotel: { id: '1', status: 'ACTIVE' }, created: true })
    expect(wrapper.emitted('update:modelValue').at(-1)).toEqual([false])
  })

  it('编辑已停用的酒店：回填 DISABLED 并原样提交，不会静默改成启用', async () => {
    updateHotel.mockResolvedValue({ ...disabledHotel, name: '苍山脚下的演示酒店（改名）' })
    const wrapper = mountDialog(disabledHotel)
    await flushPromises()

    expect(wrapper.find('select').element.value).toBe('DISABLED')
    expect(field(wrapper, '经度').element.value).toBe('100.1005')
    expect(field(wrapper, '联系电话').element.value).toBe('0872-1234567')

    await field(wrapper, '酒店名称').setValue('苍山脚下的演示酒店（改名）')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()

    expect(updateHotel).toHaveBeenCalledTimes(1)
    expect(updateHotel.mock.calls[0][0]).toBe('31')
    expect(updateHotel.mock.calls[0][1]).toMatchObject({
      name: '苍山脚下的演示酒店（改名）',
      status: 'DISABLED',
      contactPhone: '0872-1234567',
      longitude: 100.1005,
      latitude: 25.6896
    })
    // 修改走的是 PUT：记录位置不变，调用方不该把列表刷回第 1 页。
    expect(wrapper.emitted('saved')[0][0]).toEqual({
      hotel: { ...disabledHotel, name: '苍山脚下的演示酒店（改名）' },
      created: false
    })
  })

  it('编辑：清空坐标、地址与联系方式时提交 null，PUT 才能真正清空库内字段', async () => {
    updateHotel.mockResolvedValue(disabledHotel)
    const wrapper = mountDialog(disabledHotel)
    await flushPromises()

    await field(wrapper, '经度').setValue('')
    await field(wrapper, '纬度').setValue('')
    await field(wrapper, '详细地址').setValue('')
    await field(wrapper, '联系电话').setValue('   ')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()

    const payload = updateHotel.mock.calls[0][1]
    expect(payload.longitude).toBeNull()
    expect(payload.latitude).toBeNull()
    expect(payload.address).toBeNull()
    expect(payload.contactPhone).toBeNull()
  })

  it('页面校验：必填缺失、联系电话超长与坐标超范围时不发请求', async () => {
    const wrapper = mountDialog()
    await flushPromises()

    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(createHotel).not.toHaveBeenCalled()

    await field(wrapper, '酒店名称').setValue('大理演示酒店')
    await field(wrapper, '数据来源说明').setValue('团队测试数据')
    // 联系电话的契约上限是 20 个字符
    await field(wrapper, '联系电话').setValue('1'.repeat(21))
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(createHotel).not.toHaveBeenCalled()

    await field(wrapper, '联系电话').setValue('0872-1234567')
    await field(wrapper, '经度').setValue('181')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(createHotel).not.toHaveBeenCalled()

    // 纬度范围是 ±90，经度合法值不能因为复用同一套校验被误拒。
    await field(wrapper, '经度').setValue('100.165')
    await field(wrapper, '纬度').setValue('91')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(createHotel).not.toHaveBeenCalled()

    // 坐标全部合法后应当放行，避免上面的断言因为"永远不发请求"而假通过。
    await field(wrapper, '纬度').setValue('25.694')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(createHotel).toHaveBeenCalledTimes(1)
  })

  /**
   * 页面校验的边界必须与契约 maxLength 对齐：恰好到上限要放行，多一个字符才拦下。
   * 边界差一位（`>=` 写成 `>` 或反之）会让运营要么永远提交不上、要么把必然 422 的请求发出去。
   */
  it('校验边界与契约 maxLength 一致：恰好到上限放行，超一个字符拦下', async () => {
    createHotel.mockResolvedValue({ id: '1', status: 'ACTIVE' })
    const wrapper = mountDialog()
    await flushPromises()

    await field(wrapper, '酒店名称').setValue('名'.repeat(128))
    await field(wrapper, '联系电话').setValue('1'.repeat(20))
    await field(wrapper, '数据来源说明').setValue('源'.repeat(500))
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(createHotel).toHaveBeenCalledTimes(1)

    // 名称多一个字符：必须拦在本地，不再发第二次请求
    await field(wrapper, '酒店名称').setValue('名'.repeat(129))
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(createHotel).toHaveBeenCalledTimes(1)

    // 数据来源说明多一个字符同样拦下
    await field(wrapper, '酒店名称').setValue('名'.repeat(128))
    await field(wrapper, '数据来源说明').setValue('源'.repeat(501))
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(createHotel).toHaveBeenCalledTimes(1)
  })

  /**
   * 长度上限按 Unicode 码点算（契约 maxLength 的口径，与后端 @CodePointLength 一致），
   * 不是 JS 的 UTF-16 码元：用 String#length 会把 100 个 emoji 的酒店名算成 200 而误拦。
   */
  it('长度校验按码点计数：码元超限但码点合法的内容不会被误拦', async () => {
    createHotel.mockResolvedValue({ id: '1', status: 'ACTIVE' })
    const wrapper = mountDialog()
    await flushPromises()

    const emojiName = '😀'.repeat(100) // 100 码点 / 200 码元
    const emojiIntro = '😀'.repeat(6000) // 6000 码点 / 12000 码元
    expect(emojiName.length).toBeGreaterThan(128)
    expect(emojiIntro.length).toBeGreaterThan(10000)

    await field(wrapper, '酒店名称').setValue(emojiName)
    await field(wrapper, '酒店简介').setValue(emojiIntro)
    await field(wrapper, '数据来源说明').setValue('团队测试数据')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()

    expect(createHotel).toHaveBeenCalledTimes(1)
    expect(createHotel.mock.calls[0][0].name).toBe(emojiName)
    expect(createHotel.mock.calls[0][0].intro).toBe(emojiIntro)

    // 超出码点上限（129 > 128）仍然要拦下，别把"按码点算"做成"不校验"
    await field(wrapper, '酒店名称').setValue('😀'.repeat(129))
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(createHotel).toHaveBeenCalledTimes(1)
  })

  it('后端校验失败时就地展示 message，且不关闭弹窗、不丢用户输入', async () => {
    createHotel.mockRejectedValue(
      Object.assign(new Error('数据来源说明不能为空'), { status: 422, code: 'VALIDATION_ERROR' })
    )
    const wrapper = mountDialog()
    await flushPromises()

    await field(wrapper, '酒店名称').setValue('大理演示酒店')
    await field(wrapper, '数据来源说明').setValue('团队测试数据')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('数据来源说明不能为空')
    expect(wrapper.emitted('saved')).toBeUndefined()
    expect(field(wrapper, '酒店名称').element.value).toBe('大理演示酒店')
  })
})
