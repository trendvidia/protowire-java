// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
//
// Generates pb/src/test/resources/golden/pb-go-golden.txt: each line is
// "<case>\t<hex>", the bytes protowire-go's encoding/pb writes for the struct
// of the same name in PbGoldenTest. Run from a scratch module whose go.mod
// is
//
//   module pbgolden
//   require github.com/trendvidia/protowire-go v0.0.0
//   replace github.com/trendvidia/protowire-go => ../protowire-go
//
// with `go run . > pb-go-golden.txt` (prepend the header lines). This file
// is not compiled by Gradle; it is the recipe, kept beside its output.
package main

import (
	"encoding/hex"
	"fmt"
	"math"
	"math/big"

	"github.com/trendvidia/protowire-go/encoding/pb"
)

type Ints struct {
	A  int64            `protowire:"1"`
	B  int64            `protowire:"2"`
	C  int32            `protowire:"3"`
	M  map[string]int64 `protowire:"4"`
	MI map[string]int32 `protowire:"5"`
}
type Age struct {
	Age int32 `protowire:"1"`
}
type MinLong struct {
	V int64 `protowire:"1"`
}
type Zigzag struct {
	D int64 `protowire:"1,zigzag"`
	E int32 `protowire:"2,zigzag"`
}
type Packed struct {
	Xs []int32   `protowire:"1"`
	Ys []int64   `protowire:"2"`
	Bs []bool    `protowire:"3"`
	Ds []float64 `protowire:"4"`
	Fs []float32 `protowire:"5"`
}
type ZeroMapEntry struct {
	M map[string]string `protowire:"1"`
	Z map[string]int32  `protowire:"2"`
	K map[int32]string  `protowire:"3"`
}
type Decimal struct {
	D *big.Rat `protowire:"1"`
}
type Leaf struct {
	Name string `protowire:"1"`
	N    int32  `protowire:"2"`
}
type Nested struct {
	S  string   `protowire:"1"`
	Ls []Leaf   `protowire:"2"`
	Ss []string `protowire:"3"`
}

func dump(name string, v any) {
	b, err := pb.Marshal(v)
	if err != nil {
		panic(err)
	}
	fmt.Printf("%s\t%s\n", name, hex.EncodeToString(b))
}

func main() {
	dump("Ints", &Ints{A: -1, B: -1, C: -1, M: map[string]int64{"k": -1}, MI: map[string]int32{"k": -1}})
	dump("Age", &Age{Age: 30})
	dump("MinLong", &MinLong{V: math.MinInt64})
	dump("Zigzag", &Zigzag{D: -1, E: -2})
	dump("Packed", &Packed{Xs: []int32{1, 0, -1}, Ys: []int64{300}, Bs: []bool{true, false}, Ds: []float64{1.5}, Fs: []float32{-2.5}})
	dump("ZeroMapEntry", &ZeroMapEntry{M: map[string]string{"": ""}, Z: map[string]int32{"zero": 0}, K: map[int32]string{0: "v"}})
	dump("Decimal", &Decimal{D: big.NewRat(31415, 10000)})
	dump("Nested", &Nested{S: "x", Ls: []Leaf{{}, {Name: "l", N: -3}}, Ss: []string{"", "s"}})
}
