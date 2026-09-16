/* {参数} 占位符高亮 ViewPlugin
   CM6 语法高亮不认识 #{参数名} / ${参数名} / {参数名}，这里用 Decoration 加橙色底纹，
   保留脚本参数视觉提示。
   build 全程 try/catch：任何异常都不影响编辑器正常编辑（降级为空装饰） */

import { ViewPlugin, Decoration } from '@codemirror/view'
import { RangeSetBuilder } from '@codemirror/state'

const paramMark = Decoration.mark({ class: 'cm-param' })
const paramRe = /[#$]?\{[^{}\n]+\}/g

export const paramDecoration = ViewPlugin.fromClass(class {
  constructor(view) {
    this.decorations = this.build(view)
  }

  update(update) {
    if (update.docChanged || update.viewportChanged) {
      this.decorations = this.build(update.view)
    }
  }

  build(view) {
    try {
      const builder = new RangeSetBuilder()
      for (const { from, to } of view.visibleRanges) {
        const text = view.state.doc.sliceString(from, to)
        let m
        paramRe.lastIndex = 0
        while ((m = paramRe.exec(text))) {
          builder.add(from + m.index, from + m.index + m[0].length, paramMark)
        }
      }
      return builder.finish()
    } catch (e) {
      console.warn('[paramDecoration] 占位符高亮计算失败，已降级为空装饰:', e)
      return Decoration.none
    }
  }
}, {
  decorations: v => v.decorations
})
