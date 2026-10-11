import { h, type FunctionalComponent, type VNodeChild } from 'vue'
import type { LeaseSource } from '../api/types'
import type { AnswerNode } from '../composables/answer'
import CitationChip from './CitationChip.vue'

type Open = (source: LeaseSource, trigger: HTMLElement) => void

function view(node: AnswerNode, open: Open): VNodeChild {
  switch (node.kind) {
    case 'text':
      return node.text
    case 'cite':
      return h(CitationChip, { source: node.source, onOpen: open })
    case 'element':
      return h(node.tag, node.attrs, node.children.map((child) => view(child, open)))
  }
}

/**
 * Draws an answer from its nodes: text is text, an element is a native element of the sanitised HTML, and a citation
 * is a chip. Nothing is parsed from a string here, so no markup can come into the page through a text.
 */
export const AnswerNodes: FunctionalComponent<
  { nodes: AnswerNode[] },
  { open: [source: LeaseSource, trigger: HTMLElement] }
> = (props, { emit }) => props.nodes.map((node) => view(node, (source, trigger) => emit('open', source, trigger)))

AnswerNodes.props = ['nodes']
AnswerNodes.emits = ['open']
