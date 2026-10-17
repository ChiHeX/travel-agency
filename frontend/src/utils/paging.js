/**
 * 分页取数工具，供需要"候选项列表"的页面使用（例如线路行程里的酒店 / 景点下拉）。
 *
 * <p>契约（docs/API.md 第 4.1 节）规定列表接口的 `size` 上限是 100，后台列表默认只返回第一页。
 * 候选项只取第一页时，第 101 条之后的资源在界面上<b>永远选不到</b>（既看不到也没法安排进行程），
 * 而且没有任何提示 —— 使用者只会以为"这条数据不存在"。这里按 size=100 逐页取全量。</p>
 *
 * <p>`maxPages` 是兜底上限：数据量异常或后端 totalPages 返回非法值时，避免页面上出现
 * 无上限的连续请求（100 × 50 = 5000 条候选项已远超本项目的合理规模）。</p>
 */

/** 契约规定的列表页大小上限。 */
export const CONTRACT_MAX_PAGE_SIZE = 100

/** 最多取多少页，防止 totalPages 异常时无限翻页。 */
export const MAX_PAGES = 50

/**
 * 逐页拉取并拼成一个数组。
 *
 * @param {(params: {page: number, size: number}) => Promise<{items?: unknown[], totalPages?: number}>} fetchPage
 *        接受 `{ page, size }` 的分页查询函数（例如 `adminApi.hotels`）
 * @returns {Promise<unknown[]>} 全量候选项；首页就为空时返回 `[]`
 */
export async function fetchAllPages(fetchPage, { size = CONTRACT_MAX_PAGE_SIZE, maxPages = MAX_PAGES } = {}) {
  const first = await fetchPage({ page: 1, size })
  const items = [...(first?.items || [])]

  const totalPages = Number(first?.totalPages ?? 0)
  const lastPage = Number.isFinite(totalPages) ? Math.min(Math.max(totalPages, 0), maxPages) : 0
  for (let page = 2; page <= lastPage; page += 1) {
    const next = await fetchPage({ page, size })
    items.push(...(next?.items || []))
  }
  return items
}
