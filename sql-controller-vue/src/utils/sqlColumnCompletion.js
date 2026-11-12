/* 列名联想补全源：按当前语句 FROM/JOIN 引用的表给出列名。
   与 sqlWords.js 的白名单源并存（表名/函数/关键字仍由语方源提供）。 */

import { syntaxTree } from '@codemirror/language'
import { isColumnPosition, statementAround, tableRefsOf } from './sqlColumnContext.js'

export function makeColumnCompletionSource(getIndex) {
  return context => {
    const { state, pos } = context
    const word = context.matchBefore(/[\w$]*/)
    if (!word || (word.from === word.to && !context.explicit)) return null

    const before = state.sliceDoc(Math.max(0, word.from - 200), word.from)
    if (!isColumnPosition(before)) return null

    const node = syntaxTree(state).resolveInner(pos, -1)
    if (/Comment|String/.test(node.name)) return null

    const index = getIndex()
    if (!index) return null
    const names = tableRefsOf(statementAround(state, pos))
    const options = []
    const seen = new Set()
    for (const name of names) {
      const table = index[name]
      if (!table) continue
      for (const col of table.columns) {
        const label = col?.name
        if (!label || seen.has(label.toLowerCase())) continue
        seen.add(label.toLowerCase())
        options.push({ label, type: 'property', detail: table.name })
      }
    }
    return options.length ? { from: word.from, options } : null
  }
}
