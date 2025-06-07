package com.example.reifendruckapp2

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.reifendruckapp2.CalibrationFragment
import com.example.reifendruckapp2.MeasurementFragment


class ViewPagerAdapter(fragmentActivity: FragmentActivity) :
    FragmentStateAdapter(fragmentActivity) {
    override fun getItemCount(): Int = 2

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> CalibrationFragment()
            1 -> MeasurementFragment()
            else -> throw IllegalStateException("Unexpected position: $position")
        }
    }
}
