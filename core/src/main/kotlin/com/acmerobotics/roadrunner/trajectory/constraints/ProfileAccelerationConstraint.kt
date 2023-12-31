package com.acmerobotics.roadrunner.trajectory.constraints

import com.acmerobotics.roadrunner.geometry.Pose2d

class ProfileAccelerationConstraint(
    private val maxProfileAccel: Double
) : TrajectoryAccelerationConstraint {
    override fun get(s: Double, pose: Pose2d, deriv: Pose2d, baseRobotVel: Pose2d) = maxProfileAccel
}