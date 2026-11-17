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
    /// Every attribute, for the editor, in canonical order.
    pub attrs: Vec<Attr>,
    /// The whole text, when the element has exactly one text child and
    /// so its text can be edited; `None` otherwise.
    pub editable_text: Option<String>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Attr {
    /// As Xmesh stores it: `{uri}local` or `local`. Pass it back to edit.
    pub name: String,
    /// As a person reads it: `fill`, `inkscape:label`.
    pub display: String,
    pub value: String,
}

fn display_name(expanded: &str) -> String {
    let (uri, local) = split(expanded);
    match uri {
        None => local.to_string(),
        Some(uri) => match xmesh::names::FIXED_PREFIXES.iter().find(|(_, u)| *u == uri) {
            Some((prefix, _)) => format!("{prefix}:{local}"),
            None => expanded.to_string(),
        },
    }
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
        attrs: attrs
            .iter()
            .map(|(k, v)| Attr {
                name: k.clone(),
                display: display_name(k),
                value: v.clone(),
            })
            .collect(),
        editable_text: match texts(children).as_slice() {
            [(_, t)] => Some(t.clone()),
            _ => None,
        },
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

/// The attribute the drawing marks each element with, `client:clock`, so a
/// touch on the drawing finds its node. It's only in what's rendered, never
/// in the document.
pub const NODE_ATTR: &str = "data-xtxp-node";

pub fn node_key(id: NodeId) -> String {
    format!("{}:{}", id.client, id.clock)
}

/// The document as plain nodes, each element marked with [`NODE_ATTR`].
pub fn marked(fragment: &[IdNode]) -> Vec<xmesh::Node> {
    fragment.iter().map(mark).collect()
}

fn mark(n: &IdNode) -> xmesh::Node {
    match &n.node {
        IdNodeKind::Text(t) => xmesh::Node::Text(t.clone()),
        IdNodeKind::Element {
            name,
            attrs,
            children,
        } => {
            let mut attrs = attrs.clone();
            if !is_reserved(name) {
                attrs.push((NODE_ATTR.to_string(), node_key(n.id)));
            }
            xmesh::Node::Element(xmesh::Element {
                name: name.clone(),
                attrs,
                children: children.iter().map(mark).collect(),
            })
        }
    }
}

/// `transform` with `translate(dx dy)` applied first in the parent's space:
/// added to a leading translate, or put in front of whatever is there.
pub fn translated(transform: &str, dx: f64, dy: f64) -> String {
    let t = transform.trim();
    let num = |v: f64| {
        let s = format!("{:.2}", v);
        let s = s.trim_end_matches('0').trim_end_matches('.').to_string();
        if s == "-0" {
            "0".into()
        } else {
            s
        }
    };
    if let Some(rest) = t.strip_prefix("translate(") {
        if let Some((args, after)) = rest.split_once(')') {
            let nums: Vec<f64> = args
                .split(|c: char| c == ',' || c.is_whitespace())
                .filter(|s| !s.is_empty())
                .map(str::parse)
                .collect::<Result<_, _>>()
                .unwrap_or_default();
            if matches!(nums.len(), 1 | 2) {
                let (x, y) = (nums[0], nums.get(1).copied().unwrap_or(0.0));
                let after = after.trim();
                let head = format!("translate({} {})", num(x + dx), num(y + dy));
                return if after.is_empty() {
                    head
                } else {
                    format!("{head} {after}")
                };
            }
        }
    }
    let head = format!("translate({} {})", num(dx), num(dy));
    if t.is_empty() {
        head
    } else {
        format!("{head} {t}")
    }
}

/// The edit for dragging `node` by `(dx, dy)` in its parent's user units.
pub fn translate_op(
    fragment: &[IdNode],
    node: NodeId,
    dx: f64,
    dy: f64,
) -> Result<(Op, String), XtxpError> {
    let invalid = |msg: &str| XtxpError::Invalid { msg: msg.into() };
    let root = root(fragment).ok_or_else(|| invalid("the document is empty"))?;
    let (path, n) = locate(root, node).ok_or_else(|| invalid("that element is gone"))?;
    let IdNodeKind::Element { attrs, .. } = &n.node else {
        return Err(invalid("only elements move"));
    };
    if path.is_empty() {
        return Err(invalid("the root element can't move"));
    }
    let current = attrs
        .iter()
        .find(|(k, _)| k == "transform")
        .map(|(_, v)| v.as_str())
        .unwrap_or("");
    let summary = format!(
        "Dragged {} by ({}, {})",
        describe(n),
        dx.round(),
        dy.round()
    );
    Ok((
        Op::SetAttr {
            path,
            name: "transform".into(),
            value: translated(current, dx, dy),
        },
        summary,
    ))
}

/// The element's text children: their index among its children, and text.
fn texts(children: &[IdNode]) -> Vec<(u32, String)> {
    children
        .iter()
        .enumerate()
        .filter_map(|(i, c)| match &c.node {
            IdNodeKind::Text(t) => Some((i as u32, t.clone())),
            _ => None,
        })
        .collect()
}

/// One edit from the element editor: attributes to set and remove, and new
/// text if it changed, as one transaction.
pub fn edit_ops(
    fragment: &[IdNode],
    node: NodeId,
    set: &[(String, String)],
    remove: &[String],
    text: Option<&str>,
) -> Result<(Vec<Op>, String), XtxpError> {
    let invalid = |msg: &str| XtxpError::Invalid { msg: msg.into() };
    let root = root(fragment).ok_or_else(|| invalid("the document is empty"))?;
    let (path, n) = locate(root, node).ok_or_else(|| invalid("that element is gone"))?;
    let IdNodeKind::Element { children, .. } = &n.node else {
        return Err(invalid("only elements have attributes"));
    };
    let mut ops = Vec::new();
    let mut what = Vec::new();
    for name in remove {
        ops.push(Op::RemoveAttr {
            path: path.clone(),
            name: name.clone(),
        });
        what.push(format!("-{}", display_name(name)));
    }
    for (name, value) in set {
        if name.trim().is_empty() {
            return Err(invalid("an attribute needs a name"));
        }
        ops.push(Op::SetAttr {
            path: path.clone(),
            name: name.trim().to_string(),
            value: value.clone(),
        });
        what.push(display_name(name.trim()));
    }
    if let Some(new) = text {
        let runs = texts(children);
        let [(index, old)] = runs.as_slice() else {
            return Err(invalid(
                "only an element with one run of text can have it edited",
            ));
        };
        if old != new {
            let mut text_path = path.clone();
            text_path.push(*index);
            ops.extend(text_ops(text_path, old, new));
            what.push("text".into());
        }
    }
    Ok((ops, format!("Edited {}: {}", describe(n), what.join(", "))))
}

/// The ops that turn `old` text into `new`: at most one delete and one
/// insert, around their common prefix and suffix, at UTF-16 offsets (as the
/// muppet's own editors do).
fn text_ops(path: Vec<u32>, old: &str, new: &str) -> Vec<Op> {
    let (o, n): (Vec<char>, Vec<char>) = (old.chars().collect(), new.chars().collect());
    let prefix = o.iter().zip(&n).take_while(|(a, b)| a == b).count();
    let max_suffix = o.len().min(n.len()) - prefix;
    let suffix = o
        .iter()
        .rev()
        .zip(n.iter().rev())
        .take(max_suffix)
        .take_while(|(a, b)| a == b)
        .count();
    let units = |cs: &[char]| cs.iter().map(|c| c.len_utf16() as u32).sum::<u32>();
    let offset = units(&o[..prefix]);
    let removed = &o[prefix..o.len() - suffix];
    let added: String = n[prefix..n.len() - suffix].iter().collect();
    let mut ops = Vec::new();
    if !removed.is_empty() {
        ops.push(Op::DeleteText {
            path: path.clone(),
            offset,
            len: units(removed),
        });
    }
    if !added.is_empty() {
        ops.push(Op::InsertText {
            path,
            offset,
            text: added,
        });
    }
    ops
}

#[cfg(test)]
mod tests {
    use super::translated;

    #[test]
    fn translates_compose_with_what_is_there() {
        assert_eq!(translated("", 3.0, -2.5), "translate(3 -2.5)");
        assert_eq!(translated("translate(64 0)", 1.004, 2.0), "translate(65 2)");
        assert_eq!(
            translated("translate(32,0) rotate(45) scale(.606)", -2.0, 1.0),
            "translate(30 1) rotate(45) scale(.606)"
        );
        assert_eq!(
            translated("rotate(10)", 1.0, 1.0),
            "translate(1 1) rotate(10)"
        );
        assert_eq!(translated("translate(5)", -5.0, 0.0), "translate(0 0)");
    }
}
