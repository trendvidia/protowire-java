// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.

// Command go-bigfloat-text prints protowire-go's verdict on decoding each
// pxf.BigFloat literal of a list as `bf = <literal>` into bf.v1.H, one line
// per literal:
//
//	<literal> TAB <verdict>
//
// The verdict is UnmarshalFull's: "ok", or the error. Its output is
// scripts/go-bigfloat-text/go.tsv. BigFloatTextTest and BigFloatTextLiteTest
// hold both Java tiers to it: Go's text where Java gives it, and, for the
// literals far past MaxBigFloatExponent that Java names in its own words
// (#229), Java's pinned text, while Go's still differs. The conformance job
// reruns it against protowire-go main, so a change in Go's text fails there.
// Run it from a protowire-go checkout:
//
//	cd ../protowire-go && go run ../protowire-java/scripts/go-bigfloat-text/main.go \
//	    ../protowire-java/scripts/go-bigfloat-text/literals.txt \
//	    ../protowire-java/testdata ../protowire/proto \
//	    > ../protowire-java/scripts/go-bigfloat-text/go.tsv
package main

import (
	"bufio"
	"context"
	"fmt"
	"os"
	"strings"

	"github.com/trendvidia/protocompile"
	"github.com/trendvidia/protowire-go/encoding/pxf"
	"google.golang.org/protobuf/types/dynamicpb"
)

func main() {
	if len(os.Args) != 4 {
		fmt.Fprintln(os.Stderr, "usage: go-bigfloat-text <literals.txt> <testdata> <spec proto dir>")
		os.Exit(2)
	}
	c := protocompile.Compiler{Resolver: protocompile.WithStandardImports(
		&protocompile.SourceResolver{ImportPaths: []string{os.Args[2], os.Args[3]}})}
	fs, err := c.Compile(context.Background(), "bigfloat/bigfloat.proto")
	if err != nil {
		fail(err)
	}
	h := fs[0].Messages().ByName("H")
	f, err := os.Open(os.Args[1])
	if err != nil {
		fail(err)
	}
	defer f.Close()
	out := bufio.NewWriter(os.Stdout)
	defer out.Flush()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		lit := sc.Text()
		if lit == "" || strings.HasPrefix(lit, "#") {
			continue
		}
		v := "ok"
		if _, err := pxf.UnmarshalFull([]byte("bf = "+lit), dynamicpb.NewMessage(h)); err != nil {
			v = err.Error()
		}
		fmt.Fprintf(out, "%s\t%s\n", lit, v)
	}
}

func fail(err error) {
	fmt.Fprintln(os.Stderr, "go-bigfloat-text:", err)
	os.Exit(1)
}
