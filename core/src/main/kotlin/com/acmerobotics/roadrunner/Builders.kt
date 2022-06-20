package com.acmerobotics.roadrunner

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

/**
 * @usesMathJax
 *
 * Persistent builder for a [CompositePositionPath] that guarantees \(C^2\) continuity.
 */
class PositionPathBuilder private constructor(
    // invariants:
    // - segments satisfy continuity guarantees
    // - last segment ends with nextBeginPos, nextBeginTangent if it exists
    private val segments: PersistentList<PositionPath<ArcLength>>,
    private val nextBeginPos: Position2,
    private val nextBeginTangent: Rotation2,
) {
    constructor(
        beginPos: Position2,
        beginTangent: Rotation2,
    ) : this(persistentListOf(), beginPos, beginTangent)

    constructor(
        beginPos: Position2,
        beginTangent: Double
    ) : this(persistentListOf(), beginPos, Rotation2.exp(beginTangent))

    private fun addLine(line: Line): PositionPathBuilder {
        val lineEnd = line.end(2)
        return PositionPathBuilder(
            segments.add(line),
            lineEnd.value(),
            lineEnd.tangent().value(),
        )
    }

    fun forward(dist: Double) =
        addLine(Line(nextBeginPos, nextBeginPos + nextBeginTangent.vec() * dist))

    fun lineToX(posX: Double) =
        addLine(Line(nextBeginPos, Position2(posX,
                (posX - nextBeginPos.x) / nextBeginTangent.real * nextBeginTangent.imag + nextBeginPos.y
            )))

    fun lineToY(posY: Double) = addLine(
        Line(nextBeginPos,
            Position2(
                (posY - nextBeginPos.y) / nextBeginTangent.imag * nextBeginTangent.real + nextBeginPos.x, posY,
            ))
        )

    fun splineTo(pos: Position2, tangent: Rotation2): PositionPathBuilder {
        // NOTE: First derivatives will be normalized by arc length reparam, so the
        // magnitudes need not match at knots.
        val dist = (pos - nextBeginPos).norm()
        val beginDeriv = nextBeginTangent.vec() * dist
        val endDeriv = tangent.vec() * dist

        val spline = ArcCurve2(
            QuinticSpline2(
                QuinticSpline1(
                    DualNum(doubleArrayOf(nextBeginPos.x, beginDeriv.x, 0.0)),
                    DualNum(doubleArrayOf(pos.x, endDeriv.x, 0.0))
                ),
                QuinticSpline1(
                    DualNum(doubleArrayOf(nextBeginPos.y, beginDeriv.y, 0.0)),
                    DualNum(doubleArrayOf(pos.y, endDeriv.y, 0.0)),
                )
            )
        )

        val splineEnd = spline.end(2)
        return PositionPathBuilder(
            segments.add(spline),
            splineEnd.value(),
            splineEnd.tangent().value(),
        )
    }

    fun splineTo(pos: Position2, tangent: Double) = splineTo(pos, Rotation2.exp(tangent))

    fun build() = CompositePositionPath(segments)
}

// TODO: document a guarantee about continuity
class PosePathBuilder private constructor(
    // invariant: state encodes heading for [0.0, beginDisp)
    @JvmField
    val posPath: PositionPath<ArcLength>,
    @JvmField
    val beginDisp: Double,
    @JvmField
    val state: State,
) {
    constructor(path: PositionPath<ArcLength>, beginHeading: Rotation2) :
        this(path, 0.0, Lazy({ persistentListOf() }, beginHeading))

    sealed interface State {
        val endHeading: Rotation2
    }

    class Eager(
        @JvmField
        val paths: PersistentList<PosePath>, @JvmField val endHeadingDual: Rotation2Dual<ArcLength>) : State {
        override val endHeading = endHeadingDual.value()
    }

    // TODO: I can suppress these classes, though it might be better to hide the whole internal
    // state of builders; I think they are reasonably "more abstract" than the others
    /**
     * @suppress
     */
    // TODO: is it possible to make this appear as a static inner class from the Java side?
    class Lazy(@JvmField val makePaths: (Rotation2Dual<ArcLength>) -> PersistentList<PosePath>, override val endHeading: Rotation2) : State

    // TODO: keep this private?
    // pro: easier to make breaking changes in the future
    // con: more difficult to extend the builder
    private fun addEagerPosePath(disp: Double, posePath: PosePath): PosePathBuilder {
        require(disp > beginDisp)

        val beginHeadingDual = posePath.begin(3).rotation

        return PosePathBuilder(
            posPath, disp,
            Eager(
                when (state) {
                    is Eager -> {
                        // TODO: Rotation2.epsilonEquals?
                        require(state.endHeadingDual.real.epsilonEquals(beginHeadingDual.real))
                        require(state.endHeadingDual.imag.epsilonEquals(beginHeadingDual.imag))

                        state.paths
                    }
                    is Lazy -> state.makePaths(beginHeadingDual)
                }.add(posePath),
                posePath.end(3).rotation
            )
        )
    }

    private fun viewUntil(disp: Double) =
        PositionPathView(posPath, beginDisp, disp - beginDisp)

    fun tangentUntil(disp: Double) = addEagerPosePath(
        disp,
        TangentPath(
            viewUntil(disp),
            state.endHeading - posPath[disp, 2].tangent().value()
        )
    )

    fun constantUntil(disp: Double) = addEagerPosePath(
        disp,
        HeadingPosePath(
            viewUntil(disp),
            ConstantHeadingPath(state.endHeading, disp - beginDisp),
        )
    )

    fun linearUntil(disp: Double, heading: Rotation2) = addEagerPosePath(
        disp,
        HeadingPosePath(
            viewUntil(disp),
            LinearHeadingPath(state.endHeading, heading - state.endHeading, disp - beginDisp)
        )
    )

    fun splineUntil(disp: Double, heading: Rotation2): PosePathBuilder {
        require(disp > beginDisp)

        return PosePathBuilder(
            posPath, disp,
            Lazy(
                when (state) {
                    is Eager -> {
                        {
                            state.paths.add(
                                HeadingPosePath(
                                    viewUntil(disp),
                                    SplineHeadingPath(state.endHeadingDual, it, disp - beginDisp),
                                )
                            )
                        }
                    }
                    is Lazy -> {
                        {
                            val beginTangent = posPath[beginDisp, 4].tangent()
                            val beginHeading = Rotation2Dual.exp(
                                beginTangent.log().drop(1)
                                    .addFirst(state.endHeading.log())
                            )

                            state.makePaths(beginHeading).add(
                                HeadingPosePath(
                                    viewUntil(disp),
                                    SplineHeadingPath(beginHeading, it, disp - beginDisp)
                                )
                            )
                        }
                    }
                },
                heading
            )
        )
    }

    fun tangentUntilEnd() = tangentUntil(posPath.length).build()
    fun constantUntilEnd() = constantUntil(posPath.length).build()
    fun linearUntilEnd(heading: Rotation2) = linearUntil(posPath.length, heading).build()
    fun splineUntilEnd(heading: Rotation2) = splineUntil(posPath.length, heading).build()

    // NOTE: must be at the end of the pose path
    fun build(): PosePath {
        require(beginDisp == posPath.length)

        return CompositePosePath(
            when (state) {
                is Eager -> state.paths
                is Lazy -> {
                    val endTangent = posPath[beginDisp, 4].tangent()
                    val endHeading = Rotation2Dual.exp(
                        endTangent.log().drop(1)
                            .addFirst(state.endHeading.log())
                    )

                    state.makePaths(endHeading)
                }
            }
        )
    }
}

class SafePosePathBuilder internal constructor(@JvmField val posePathBuilder: PosePathBuilder) {
    constructor(path: PositionPath<ArcLength>, beginHeading: Rotation2) :
        this(PosePathBuilder(path, beginHeading))

    fun tangentUntil(disp: Double) =
        RestrictedPosePathBuilder(posePathBuilder.tangentUntil(disp))
    fun constantUntil(disp: Double) =
        RestrictedPosePathBuilder(posePathBuilder.constantUntil(disp))
    fun linearUntil(disp: Double, heading: Rotation2) =
        RestrictedPosePathBuilder(posePathBuilder.linearUntil(disp, heading))

    fun splineUntil(disp: Double, heading: Rotation2) =
        SafePosePathBuilder(posePathBuilder.splineUntil(disp, heading))

    fun tangentUntilEnd() = posePathBuilder.tangentUntilEnd()
    fun constantUntilEnd() = posePathBuilder.constantUntilEnd()
    fun linearUntilEnd(heading: Rotation2) = posePathBuilder.linearUntilEnd(heading)
    fun splineUntilEnd(heading: Rotation2) = posePathBuilder.splineUntilEnd(heading)

    fun build() = posePathBuilder.build()
}

class RestrictedPosePathBuilder internal constructor(@JvmField val posePathBuilder: PosePathBuilder) {
    fun splineUntil(disp: Double, heading: Rotation2) =
        SafePosePathBuilder(posePathBuilder.splineUntil(disp, heading))

    fun splineUntilEnd(heading: Rotation2) = posePathBuilder.splineUntilEnd(heading)

    fun build() = posePathBuilder.build()
}