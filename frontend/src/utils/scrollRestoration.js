import { nextTick } from 'vue'

const positions = new Map()
let cancelPendingRestore = () => {}
const entryIdKey = 'travelAgencyScrollEntryId'

export function getScrollEntryId() {
  const state = window.history.state
  if (!state) return null
  if (state[entryIdKey]) return state[entryIdKey]
  // A new push can reuse the numeric history position after going back.
  const id = crypto.randomUUID()
  window.history.replaceState({ ...state, [entryIdKey]: id }, '')
  return id
}

function scrollRoot() {
  return document.querySelector('.route-view-body, .admin-content')
}

function elementKey(element, root) {
  if (element === root) return 'root'
  const className = element.classList[0]
  if (!className) return null
  const matches = root.querySelectorAll(`.${CSS.escape(className)}`)
  return `${className}:${Array.prototype.indexOf.call(matches, element)}`
}

function findElement(key, root) {
  if (key === 'root') return root
  const separator = key.lastIndexOf(':')
  const className = key.slice(0, separator)
  const index = Number(key.slice(separator + 1))
  return root.querySelectorAll(`.${CSS.escape(className)}`)[index]
}

export function saveScrollPosition(entryId) {
  cancelPendingRestore()
  const root = scrollRoot()
  if (!root || entryId == null) return
  const offsets = []
  for (const element of [root, ...root.querySelectorAll('*')]) {
    if (element.scrollTop <= 0 && element.scrollLeft <= 0) continue
    const key = elementKey(element, root)
    if (key) offsets.push({ key, top: element.scrollTop, left: element.scrollLeft })
  }
  positions.set(entryId, offsets)
  if (positions.size > 30) positions.delete(positions.keys().next().value)
}

export async function restoreScrollPosition(entryId) {
  cancelPendingRestore()
  const offsets = positions.get(entryId)
  await nextTick()
  if (!offsets?.length) {
    const root = scrollRoot()
    if (root) root.scrollTop = 0
    return
  }

  let observer
  let timeout
  let frame
  let cancelled = false
  const stop = () => {
    cancelled = true
    observer?.disconnect()
    clearTimeout(timeout)
    cancelAnimationFrame(frame)
    window.removeEventListener('wheel', stop)
    window.removeEventListener('touchstart', stop)
    window.removeEventListener('keydown', stop)
  }
  cancelPendingRestore = stop

  const apply = () => {
    if (cancelled) return
    const root = scrollRoot()
    if (!root) return
    const complete = offsets.every(({ key, top, left }) => {
      const element = findElement(key, root)
      if (!element) return false
      element.scrollTo(left, top)
      return Math.abs(element.scrollTop - top) < 2 && Math.abs(element.scrollLeft - left) < 2
    })
    if (complete) stop()
  }

  const root = scrollRoot()
  if (!root) return
  observer = new MutationObserver(() => {
    cancelAnimationFrame(frame)
    frame = requestAnimationFrame(apply)
  })
  observer.observe(root, { childList: true, subtree: true })
  window.addEventListener('wheel', stop, { passive: true, once: true })
  window.addEventListener('touchstart', stop, { passive: true, once: true })
  window.addEventListener('keydown', stop, { once: true })
  timeout = setTimeout(stop, 5000)
  apply()
}
