// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.

// Command go-verdicts prints protowire-go's verdicts on each document of the
// spec corpus that both Java tiers run (testdata/), one line per document:
//
//	<dir>/<file> TAB <message type> TAB <decode> TAB <parse>
//
// Each verdict is "ok" or the error text, with its line:column. <decode> is
// UnmarshalFull's, which the full tier's unmarshalFull matches: (pxf.required)
// and (pxf.default) apply, as the lite tier always applies them. <parse> is
// Parse's, which knows no schema. The lite tier parses before it binds, so a
// document Parse rejects is held to <parse>, and the rest to <decode>. The two
// differ only where the decoder sees the schema first. The any/ documents
// resolve exactly any.v1.Leaf, as the Java corpus tests do. A dataset/
// document's <decode> is the first error binding its rows, each with BindRow
// to the type its @dataset header names, as cmd/pxf's validate binds them;
// <message type> is the first header's type. Its output is scripts/go-verdicts/corpus.tsv,
// which PxfCorpusTextTest and LiteCorpusTextTest hold each tier to, and the
// conformance job reruns it against protowire-go main to keep the table
// current. Run it from a protowire-go checkout, so its imports resolve
// through that module:
//
//	cd ../protowire-go && go run ../protowire-java/scripts/go-verdicts/main.go \
//	    ../protowire-java/testdata ../protowire/proto > ../protowire-java/scripts/go-verdicts/corpus.tsv
package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"

	"github.com/trendvidia/protocompile"
	"github.com/trendvidia/protowire-go/encoding/pxf"
	"google.golang.org/protobuf/reflect/protoreflect"
	"google.golang.org/protobuf/reflect/protoregistry"
	"google.golang.org/protobuf/types/dynamicpb"
)

// The directories, the schemas their documents name, and the type of a
// document with no @type line.
var dirs = []struct {
	dir, def string
	protos   []string
}{
	{"annotations", "settings.v1.Settings", []string{"annotations/settings.proto"}},
	{"any", "any.v1.Holder", []string{"any/any.proto"}},
	{"bigfloat", "bf.v1.H", []string{"bigfloat/bigfloat.proto"}},
	{"dataset", "test.v1.AllTypes", []string{"test.proto", "dataset/paths.proto"}},
	{"blocks", "blocks.v1.Doc", []string{"blocks/blocks.proto"}},
	{"duplicates", "dup.v1.Holder", []string{"duplicates/duplicates.proto"}},
	{"duration", "duration.v1.Holder", []string{"duration/duration.proto"}},
	{"keyed", "", []string{"keyed/keyed.proto"}},
	{"map-keys", "mapkeys.v1.Flags", []string{"map-keys/bool-keys.proto"}},
	{"nulls", "nulls.v1.Holder", []string{"nulls/nulls.proto", "nulls/nulls_proto2.proto", "nulls/nulls_editions.proto"}},
	{"repeated", "test.v1.AllTypes", []string{"test.proto"}},
	{"strings", "strings.v1.S", []string{"strings/strings.proto"}},
}

var (
	typeLine    = regexp.MustCompile(`(?m)^@type\s+(\S+)`)
	datasetLine = regexp.MustCompile(`(?m)^@dataset\s+([\w.]+)`)
)

func main() {
	if len(os.Args) != 3 {
		fmt.Fprintln(os.Stderr, "usage: go-verdicts <testdata> <spec proto dir>")
		os.Exit(2)
	}
	root, imports := os.Args[1], os.Args[2]
	var out []string
	for _, d := range dirs {
		files, err := compile(root, imports, d.protos)
		if err != nil {
			fail(err)
		}
		docs, err := documents(filepath.Join(root, d.dir))
		if err != nil {
			fail(err)
		}
		for _, path := range docs {
			rel, _ := filepath.Rel(root, path)
			name, decode, parse := verdicts(path, d.dir, d.def, files)
			out = append(out, filepath.ToSlash(rel)+"\t"+name+"\t"+decode+"\t"+parse)
		}
	}
	sort.Strings(out)
	fmt.Println(strings.Join(out, "\n"))
}

func compile(root, imports string, protos []string) (*protoregistry.Files, error) {
	c := protocompile.Compiler{Resolver: protocompile.WithStandardImports(
		&protocompile.SourceResolver{ImportPaths: []string{root, imports}})}
	fs, err := c.Compile(context.Background(), protos...)
	if err != nil {
		return nil, err
	}
	files := new(protoregistry.Files)
	for _, f := range fs {
		if err := files.RegisterFile(f); err != nil {
			return nil, err
		}
	}
	return files, nil
}

// documents are the .pxf files under dir, invalid/ included, without the
// .expected.pxf halves of pairs.
func documents(dir string) ([]string, error) {
	var docs []string
	err := filepath.WalkDir(dir, func(path string, e os.DirEntry, err error) error {
		if err == nil && !e.IsDir() && strings.HasSuffix(path, ".pxf") && !strings.HasSuffix(path, ".expected.pxf") {
			docs = append(docs, path)
		}
		return err
	})
	return docs, err
}

func verdicts(path, dir, def string, files *protoregistry.Files) (name, decode, parse string) {
	data, err := os.ReadFile(path)
	if err != nil {
		fail(err)
	}
	name = def
	if dir == "dataset" {
		if m := datasetLine.FindSubmatch(data); m != nil {
			name = string(m[1])
		}
		_, perr := pxf.Parse(data)
		return name, text(path, bindRows(path, data, files)), text(path, perr)
	}
	if m := typeLine.FindSubmatch(data); m != nil {
		name = string(m[1])
	}
	desc, err := files.FindDescriptorByName(protoreflect.FullName(name))
	if err != nil {
		fail(fmt.Errorf("%s: type %q: %w", path, name, err))
	}
	opts := pxf.UnmarshalOptions{}
	if dir == "any" {
		opts.TypeResolver = leaf{files}
	}
	_, err = opts.UnmarshalFull(data, dynamicpb.NewMessage(desc.(protoreflect.MessageDescriptor)))
	_, perr := pxf.Parse(data)
	return name, text(path, err), text(path, perr)
}

// bindRows parses a dataset document and binds each row of each dataset to
// the type its header names: Parse's error, or the first row's that fails.
func bindRows(path string, data []byte, files *protoregistry.Files) error {
	doc, err := pxf.Parse(data)
	if err != nil {
		return err
	}
	for _, ds := range doc.Datasets {
		d, err := files.FindDescriptorByName(protoreflect.FullName(ds.Type))
		if err != nil {
			fail(fmt.Errorf("%s: dataset type %q: %w", path, ds.Type, err))
		}
		for _, row := range ds.Rows {
			if err := pxf.BindRow(dynamicpb.NewMessage(d.(protoreflect.MessageDescriptor)), ds.Columns, row); err != nil {
				return err
			}
		}
	}
	return nil
}

func text(path string, err error) string {
	if err == nil {
		return "ok"
	}
	t := err.Error()
	if strings.ContainsAny(t, "\t\n") {
		fail(fmt.Errorf("%s: error text holds a tab or newline: %q", path, t))
	}
	return t
}

// leaf resolves exactly any.v1.Leaf, by the URL's last segment.
type leaf struct{ files *protoregistry.Files }

func (l leaf) FindMessageByURL(url string) (protoreflect.MessageDescriptor, error) {
	name := url[strings.LastIndex(url, "/")+1:]
	if name != "any.v1.Leaf" {
		return nil, errors.New("not found")
	}
	d, err := l.files.FindDescriptorByName(protoreflect.FullName(name))
	if err != nil {
		return nil, err
	}
	return d.(protoreflect.MessageDescriptor), nil
}

func fail(err error) {
	fmt.Fprintln(os.Stderr, "go-verdicts:", err)
	os.Exit(1)
}
