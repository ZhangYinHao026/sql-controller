<script setup>
defineOptions({ name: 'CommandTreeNode' })

const props = defineProps({
  node: { type: Object, required: true },
  selectedId: { type: [String, Number], default: null },
  childrenOf: { type: Function, required: true }
})
const emit = defineEmits(['select', 'context', 'send'])

function children() {
  return props.childrenOf(props.node.id)
}
</script>

<template>
  <div class="command-node">
    <div class="command-node-row" :class="{ selected: String(node.id) === String(selectedId) }"
         @contextmenu.prevent="emit('context', { node, event: $event })">
      <button v-if="node.nodeType === 'folder'" class="tree-toggle" @click="emit('select', node)">
        {{ node.isOpen ? '▾' : '▸' }}
      </button>
      <span v-else class="tree-file-mark">⌘</span>
      <button class="tree-name" @click="emit('select', node)">
        {{ node.nodeType === 'folder' ? '📁' : '' }} {{ node.name }}
      </button>
      <button v-if="node.nodeType === 'file'" class="tree-send" title="发送并执行" @click="emit('send', node)">➤</button>
    </div>
    <div v-if="node.nodeType === 'folder' && node.isOpen" class="tree-children">
      <CommandTreeNode v-for="child in children()" :key="child.id" :node="child" :selected-id="selectedId"
                       :children-of="childrenOf" @select="emit('select', $event)"
                       @context="payload => emit('context', payload)" @send="emit('send', $event)" />
    </div>
  </div>
</template>
