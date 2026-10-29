<script setup lang="ts">
import type { FolderNode } from '../api/notes'
import { isFolderOpen, toggleFolder } from '../lib/folderTree'

/**
 * 多级目录树（递归渲染）。
 *
 * 只负责「长什么样、怎么展开」，选中逻辑全交给父组件：
 * 树本身不知道路由和请求，换成别的导航场景也能直接用。
 */
withDefaults(defineProps<{ nodes: FolderNode[]; selected: string; depth?: number }>(), {
  depth: 0,
})

const emit = defineEmits<{ select: [path: string] }>()
</script>

<template>
  <ul class="ftree">
    <li v-for="node in nodes" :key="node.path" class="ftree-item">
      <div
        class="ftree-row"
        :class="{ 'ftree-active': node.path === selected }"
        :style="{ paddingLeft: `${6 + depth * 13}px` }"
      >
        <button
          v-if="node.children.length"
          class="ftree-caret"
          type="button"
          :aria-expanded="isFolderOpen(node.path)"
          :aria-label="isFolderOpen(node.path) ? `收起 ${node.name}` : `展开 ${node.name}`"
          @click.stop="toggleFolder(node.path)"
        >
          <svg viewBox="0 0 12 12" aria-hidden="true" :class="{ 'caret-open': isFolderOpen(node.path) }">
            <path d="M4 2.5 L8 6 L4 9.5" />
          </svg>
        </button>
        <span v-else class="ftree-caret ftree-caret-empty" aria-hidden="true"></span>

        <button class="ftree-label" type="button" :title="node.path" @click="emit('select', node.path)">
          {{ node.name }}
        </button>
        <span class="ftree-count">{{ node.count }}</span>
      </div>

      <FolderTree
        v-if="node.children.length && isFolderOpen(node.path)"
        :nodes="node.children"
        :selected="selected"
        :depth="depth + 1"
        @select="emit('select', $event)"
      />
    </li>
  </ul>
</template>

<style scoped>
.ftree {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 1px;
}

.ftree-row {
  position: relative;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 8px 6px 6px;
  border-radius: var(--radius);
  color: var(--ink-2);
  font-size: var(--text-sm);
  transition:
    background-color var(--dur-1) var(--ease-soft),
    color var(--dur-1) var(--ease-soft);
}

.ftree-row:hover {
  background: var(--row-hover);
  color: var(--ink);
}

/* 选中项用左侧竖条标记，比整块换底色更轻，和上面的「库」列表保持同一套语言 */
.ftree-row::before {
  content: '';
  position: absolute;
  top: 7px;
  bottom: 7px;
  left: 0;
  width: 2px;
  border-radius: 1px;
  background: var(--pen);
  transform: scaleY(0);
  transition: transform var(--dur-2) var(--ease-out);
}

.ftree-active {
  background: var(--pen-soft);
  color: var(--pen-ink);
  font-weight: 500;
}

.ftree-active::before {
  transform: scaleY(1);
}

.ftree-active .ftree-count {
  color: var(--pen);
}

.ftree-caret {
  flex: none;
  display: grid;
  place-items: center;
  width: 16px;
  height: 16px;
  padding: 0;
  border: 0;
  border-radius: 3px;
  background: none;
  color: var(--ink-3);
  cursor: pointer;
}

.ftree-caret:hover {
  color: var(--pen);
  background: var(--row-hover);
}

.ftree-caret-empty {
  cursor: default;
}

.ftree-caret svg {
  width: 10px;
  height: 10px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
  transition: transform var(--dur-1) var(--ease-soft);
}

.ftree-caret svg.caret-open {
  transform: rotate(90deg);
}

.ftree-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  padding: 0;
  border: 0;
  background: none;
  color: inherit;
  font: inherit;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}

.ftree-count {
  flex: none;
  color: var(--ink-3);
  font-size: var(--text-xs);
  font-variant-numeric: tabular-nums;
}
</style>
