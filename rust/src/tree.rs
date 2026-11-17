//! The document as rows for a drag-and-drop list, and drops turned into
//! Xmesh moves. A move is a delete plus an inserted copy (decision 20), so the
//! moved node comes back with a new ID; the UI follows it by position.

use crate::XtxpError;
use xmesh::names::{is_reserved, split};
use xmesh::{IdNode, IdNodeKind, NodeId, Op};

/// One element, flattened in document order.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Row {
    pub client: u64,
    pub clock: u32,
    pub depth: u32,
    /// Local name, such as `rect`.
    pub name: String,
    /// A short description: the `id`, or a label, or the first attribute.
    pub label: String,
    /// The element's own text, trimmed and shortened.
    pub text: String,
    pub child_elements: u32,
}

/// Where a dragged row lands relative to the row it's dropped on.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Drop {
    Before,
    /// As the target's last child.
    Into,
    After,
}

pub fn rows(fragment: &[IdNode]) -> Vec<Row> {
    let mut out = Vec::new();
    if let Some(root) = root(fragment) {
        push_rows(root, 0, &mut out);
    }
    out
}

fn root(fragment: &[IdNode]) -> Option<&IdNode> {
    fragment.iter().find(|n| match &n.node {
        IdNodeKind::Element { name, .. } => !is_reserved(name),
        IdNodeKind::Text(_) => false,
    })
}

fn push_rows(node: &IdNode, depth: u32, out: &mut Vec<Row>) {
    let IdNodeKind::Element {
        name,
        attrs,
        children,
    } = &node.node
    else {
        return;
    };
    if is_reserved(name) {
        return;
    }
    let text: String = children
        .iter()
        .filter_map(|c| match &c.node {
            IdNodeKind::Text(t) => Some(t.as_str()),
            _ => None,
        })
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ");
    let elements = children.iter().filter(|c| is_element(c)).count() as u32;
    out.push(Row {
        client: node.id.client,
        clock: node.id.clock,
        depth,
        name: local(name).to_string(),
        label: label(attrs),
        text: shorten(&text, 40),
        child_elements: elements,
    });
    for child in children {
        push_rows(child, depth + 1, out);
    }
}

fn is_element(n: &IdNode) -> bool {
    matches!(&n.node, IdNodeKind::Element { name, .. } if !is_reserved(name))
}

fn local(expanded: &str) -> &str {
    split(expanded).1
}

fn label(attrs: &[(String, String)]) -> String {
    let find = |want: &str| {
        attrs
            .iter()
            .find(|(k, _)| local(k) == want)
            .map(|(_, v)| v.clone())
    };
    if let Some(l) = find("label") {
        return l;
    }
    if let Some(id) = find("id") {
        return format!("#{id}");
    }
    attrs
        .first()
        .map(|(k, v)| format!("{}={}", local(k), shorten(v, 24)))
        .unwrap_or_default()
}

fn shorten(s: &str, max: usize) -> String {
    if s.chars().count() <= max {
        s.to_string()
    } else {
        format!("{}…", s.chars().take(max - 1).collect::<String>())
    }
}

/// A node's path from the root element (as [`Op`] uses), and the node.
fn locate(root: &IdNode, id: NodeId) -> Option<(Vec<u32>, &IdNode)> {
    fn walk<'a>(node: &'a IdNode, id: NodeId, path: &mut Vec<u32>) -> Option<&'a IdNode> {
        if node.id == id {
            return Some(node);
        }
        if let IdNodeKind::Element { children, .. } = &node.node {
            for (i, child) in children.iter().enumerate() {
                path.push(i as u32);
                if let Some(found) = walk(child, id, path) {
                    return Some(found);
                }
                path.pop();
            }
        }
        None
    }
    let mut path = Vec::new();
    walk(root, id, &mut path).map(|n| (path, n))
}

fn describe(node: &IdNode) -> String {
    match &node.node {
        IdNodeKind::Element { name, attrs, .. } => {
            let l = label(attrs);
            if l.is_empty() {
                format!("<{}>", local(name))
            } else {
                format!("<{} {l}>", local(name))
            }
        }
        IdNodeKind::Text(_) => "text".into(),
    }
}

/// The move for a drop, and a one-line summary of it for the pending list.
pub fn move_op(
    fragment: &[IdNode],
    node: NodeId,
    target: NodeId,
    drop: Drop,
) -> Result<(Op, String), XtxpError> {
    let invalid = |msg: &str| XtxpError::Invalid { msg: msg.into() };
    let root = root(fragment).ok_or_else(|| invalid("the document is empty"))?;
    let (path, moved) = locate(root, node).ok_or_else(|| invalid("that element is gone"))?;
    let (target_path, target_node) =
        locate(root, target).ok_or_else(|| invalid("the drop target is gone"))?;
    if path.is_empty() {
        return Err(invalid("the root element can't move"));
    }
    if target_path.starts_with(&path) {
        return Err(invalid("an element can't move into itself"));
    }
    let (new_parent, index, how) = match drop {
        Drop::Into => {
            let IdNodeKind::Element { children, .. } = &target_node.node else {
                return Err(invalid("only elements take children"));
            };
            (target_path.clone(), children.len() as u32, "into")
        }
        Drop::Before | Drop::After => {
            let Some((&last, parent)) = target_path.split_last() else {
                return Err(invalid("nothing can sit beside the root element"));
            };
            let index = if drop == Drop::Before { last } else { last + 1 };
            let how = if drop == Drop::Before {
                "before"
            } else {
                "after"
            };
            (parent.to_vec(), index, how)
        }
    };
    let summary = format!("Moved {} {how} {}", describe(moved), describe(target_node));
    Ok((
        Op::Move {
            path,
            new_parent,
            index,
        },
        summary,
    ))
}
