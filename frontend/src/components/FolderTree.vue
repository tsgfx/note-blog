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
  <ul class="ftree" :class="{ 'ftree-nested': depth > 0 }">
    <li v-for="node in nodes" :key="node.path" class="ftree-item">
      <div class="ftree-row" :class="{ 'ftree-active': node.path === selected, 'ftree-root': depth === 0 }">
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
  /*
   * 必须显式给 minmax(0, 1fr)。grid 的隐式列是 auto，下层节点的
   * `white-space: nowrap` 会把它的 min-content 顶到整条目录名那么宽——
   * 二三级列表于是比侧栏还宽（实测 276px vs 208px），文字被侧栏边缘硬切，
   * 右侧的篇数直接被挤出可视区。minmax(0, …) 让列可以被压缩，省略号才有机会生效。
   */
  grid-template-columns: minmax(0, 1fr);
}

.ftree-item {
  min-width: 0;
}

/*
 * 层级用「缩进 + 竖向导引线」一起表达，而不是只靠 13px 的 padding。
 * 只靠缩进时，深层条目看起来像"文字随机右移"——尤其目录名本身带空格或
 * 中文标点时，二三级的边界完全看不出来。加一条 1px 的竖线后，同一父级下
 * 的兄弟条目被串成一根树枝，层级关系一眼可见。
 *
 * 导引线画在 nested 的左侧，位置对齐父级 caret 的中心（16px + gap/2）。
 */
.ftree-nested {
  margin-left: 13px;
  padding-left: 5px;
  border-left: 1px solid var(--hairline);
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

/* 一级目录（库）稍重一档：它是这一列里"最大的分类"，不该和叶子同级 */
.ftree-root {
  color: var(--ink);
  font-weight: 500;
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
